package zio.opentelemetry.tracing

import java.util.concurrent.TimeUnit

import io.opentelemetry.common.AttributeValue
import io.opentelemetry.context.propagation.HttpTextFormat
import io.opentelemetry.trace.{ DefaultSpan, Span, Tracer }
import zio.clock.Clock
import zio.opentelemetry.tracing.attributevalue.AttributeValueConverter
import zio.opentelemetry.tracing.attributevalue.AttributeValueConverter.toAttributeValue
import zio.opentelemetry.tracing.ContextPropagation.{ extractSpan, injectSpan }
import zio.opentelemetry.tracing.SpanUtils.{ endSpan, isInvalid, setErrorStatus }
import zio._

import scala.jdk.CollectionConverters._

object Tracing {
  trait Service {
    private[opentelemetry] val currentSpan: FiberRef[Span]
    private[opentelemetry] def createRoot(spanName: String, spanKind: Span.Kind): UIO[Span]
    private[opentelemetry] def createChildOf(spanName: String, parent: Span, spanKind: Span.Kind): UIO[Span]
  }

  /**
   * Reference to the current span of the fiber.
   */
  private def currentSpan: URIO[Tracing, FiberRef[Span]] =
    ZIO.access[Tracing](_.get.currentSpan)

  /**
   * Creates a new root span and sets it to be the current span.
   */
  private def createRoot(spanName: String, spanKind: Span.Kind): URIO[Tracing, Span] =
    ZIO.accessM[Tracing](_.get.createRoot(spanName, spanKind))

  /**
   * Creates a new child span from the parent span, and sets it to be the current span.
   */
  private def createChildOf(spanName: String, parent: Span, spanKind: Span.Kind): URIO[Tracing, Span] =
    ZIO.accessM[Tracing](_.get.createChildOf(spanName, parent, spanKind))

  /**
   * Gets the current span.
   */
  private def getCurrentSpan: ZIO[Tracing, Nothing, Span] = currentSpan.flatMap(_.get)

  /**
   * Sets the current span.
   */
  private def setCurrentSpan(span: Span): ZIO[Tracing, Nothing, Unit] =
    currentSpan.flatMap(_.set(span))

  /**
   * Ends the span `newSpan` according to the result of `effect`.
   * Reverts the current span to `oldSpan` after ending the span.
   */
  private def finalizeSpanUsingEffect[R, E, A](
    effect: ZIO[R, E, A],
    oldSpan: Span,
    newSpan: Span
  ): ZIO[R with Clock with Tracing, E, A] =
    effect
      .tapCause(setErrorStatus(newSpan, _))
      .ensuring(endSpan(newSpan) *> (if (isInvalid(oldSpan)) UIO.unit else setCurrentSpan(oldSpan)))

  /**
   * Extracts the span from carrier `C` and set its child span with name 'spanName' as the current span.
   * Ends the span when the effect finishes.
   */
  def spanFrom[C, R, E, A](
    httpTextFormat: HttpTextFormat,
    carrier: C,
    reader: PropagationFormat.Reader[C],
    spanName: String,
    spanKind: Span.Kind = Span.Kind.INTERNAL
  )(effect: ZIO[R, E, A]): ZIO[R with Clock with Tracing, E, A] =
    for {
      old            <- getCurrentSpan
      extractedSpan  <- extractSpan(httpTextFormat, carrier, reader)
      extractedChild <- createChildOf(spanName, extractedSpan, spanKind)
      r              <- finalizeSpanUsingEffect(effect, old, extractedChild)
    } yield r

  /**
   * Sets the current span to be the new root span with name 'spanName'
   * Ends the span when the effect finishes.
   */
  def rootSpan[R, E, A](
    spanName: String,
    spanKind: Span.Kind = Span.Kind.INTERNAL
  )(effect: ZIO[R, E, A]): ZIO[R with Clock with Tracing, E, A] =
    for {
      old  <- getCurrentSpan
      root <- createRoot(spanName, spanKind)
      r    <- finalizeSpanUsingEffect(effect, old, root)
    } yield r

  /**
   * Sets the current span to be the child of the current span with name 'spanName'
   * Ends the span when the effect finishes.
   */
  def childSpan[R, E, A](
    spanName: String,
    spanKind: Span.Kind = Span.Kind.INTERNAL
  )(effect: ZIO[R, E, A]): ZIO[R with Clock with Tracing, E, A] =
    for {
      old   <- getCurrentSpan
      child <- createChildOf(spanName, old, spanKind)
      r     <- finalizeSpanUsingEffect(effect, old, child)
    } yield r

  /**
   * Injects the current span into carrier `C`
   */
  def injectCurrentSpan[C](
    httpTextFormat: HttpTextFormat,
    carrier: C,
    writer: PropagationFormat.Writer[C]
  ): RIO[Tracing, Unit] =
    for {
      current <- getCurrentSpan
      _       <- injectSpan(current, httpTextFormat, carrier, writer)
    } yield ()

  /**
   * Adds an event to the current span
   */
  def addEvent(name: String): URIO[Tracing with Clock, Unit] =
    for {
      nanoSeconds <- currentNanos
      span        <- getCurrentSpan
    } yield span.addEvent(name, nanoSeconds)

  /**
   * Adds an event with attributes to the current span.
   */
  def addEventWithAttributes(
    name: String,
    attributes: Map[String, AttributeValue]
  ): URIO[Tracing with Clock, Unit] =
    for {
      nanoSeconds <- currentNanos
      span        <- getCurrentSpan
    } yield span.addEvent(name, attributes.asJava, nanoSeconds)

  /**
   * Sets an attribute of the current span.
   */
  def setAttribute[A: AttributeValueConverter](name: String, value: A): URIO[Tracing with Clock, Unit] =
    getCurrentSpan.map(_.setAttribute(name, toAttributeValue(value)))

  def live(tracer: Tracer): URLayer[Clock, Tracing] = {
    class Live(tracer: Tracer, rootSpan: FiberRef[Span], clock: Clock.Service) extends Service {
      def createRoot(spanName: String, spanKind: Span.Kind): UIO[Span] =
        for {
          nanoSeconds <- clock.currentTime(TimeUnit.NANOSECONDS)
          span <- UIO(
                   tracer
                     .spanBuilder(spanName)
                     .setNoParent()
                     .setSpanKind(spanKind)
                     .setStartTimestamp(nanoSeconds)
                     .startSpan()
                 )
          _ <- currentSpan.set(span)
        } yield span

      def createChildOf(spanName: String, parent: Span, spanKind: Span.Kind): UIO[Span] =
        for {
          span <- UIO(tracer.spanBuilder(spanName).setParent(parent).setSpanKind(spanKind).startSpan())
          _    <- currentSpan.set(span)
        } yield span

      val currentSpan: FiberRef[Span] = rootSpan
    }

    def createOpenTelemetry(tracer: Tracer): URIO[Clock, Service] =
      for {
        clock    <- ZIO.access[Clock](_.get)
        rootSpan <- FiberRef.make[Span](DefaultSpan.getInvalid)
      } yield new Live(tracer, rootSpan, clock)

    ZLayer.fromAcquireRelease(createOpenTelemetry(tracer))(_.currentSpan.get.flatMap(endSpan))
  }
}
