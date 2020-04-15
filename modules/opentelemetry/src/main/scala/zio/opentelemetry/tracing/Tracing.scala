package zio.opentelemetry.tracing

import java.util.concurrent.TimeUnit

import io.opentelemetry.common.AttributeValue
import io.opentelemetry.context.propagation.HttpTextFormat
import io.opentelemetry.trace.{ DefaultSpan, Span, Tracer }
import zio.clock.Clock
import zio.opentelemetry.tracing.attributevalue.AttributeValueConverter
import zio.opentelemetry.tracing.attributevalue.AttributeValueConverter.toAttributeValue
import zio.opentelemetry.tracing.ContextPropagation.{ extractSpan, injectSpan }
import zio.opentelemetry.tracing.SpanUtils.{ endSpan, setErrorStatus }
import zio._

import scala.jdk.CollectionConverters._

object Tracing {
  trait Service {
    private[opentelemetry] val currentSpan: FiberRef[Span]
    private[opentelemetry] def createRoot(spanName: String, spanKind: Span.Kind): UIO[Span]
    private[opentelemetry] def createChildOf(parent: Span, spanName: String, spanKind: Span.Kind): UIO[Span]
  }

  /**
   * Reference to the current span of the fiber.
   */
  private def currentSpan: URIO[Tracing, FiberRef[Span]] =
    ZIO.access[Tracing](_.get.currentSpan)

  /**
   * Creates a new root span.
   */
  private def createRoot(spanName: String, spanKind: Span.Kind): URIO[Tracing, Span] =
    ZIO.accessM[Tracing](_.get.createRoot(spanName, spanKind))

  /**
   * Creates a new child span from the parent span.
   */
  private def createChildOf(parent: Span, spanName: String, spanKind: Span.Kind): URIO[Tracing, Span] =
    ZIO.accessM[Tracing](_.get.createChildOf(parent, spanName, spanKind))

  /**
   * Gets the current span.
   */
  private def getCurrentSpan: URIO[Tracing, Span] = currentSpan.flatMap(_.get)

  /**
   * Sets the `currentSpan` to `newSpan` only while `effect` runs.
   * Then ends the span `newSpan` according to the result of `effect`.
   */
  private def finalizeSpanUsingEffect[R, E, A](
    effect: ZIO[R, E, A],
    newSpan: Span
  ): ZIO[R with Clock with Tracing, E, A] =
    for {
      current <- currentSpan
      r <- current
            .locally(newSpan)(effect)
            .tapCause(setErrorStatus(newSpan, _))
            .ensuring(endSpan(newSpan))
    } yield r

  /**
   * Extracts the span from carrier `C` and set its child span with name 'spanName' as the current span.
   * Ends the span when the effect finishes.
   */
  def spanFrom[C, R, E, A](
    httpTextFormat: HttpTextFormat,
    carrier: C,
    getter: HttpTextFormat.Getter[C],
    spanName: String,
    spanKind: Span.Kind = Span.Kind.INTERNAL
  )(effect: ZIO[R, E, A]): ZIO[R with Clock with Tracing, E, A] =
    for {
      extractedSpan  <- extractSpan(httpTextFormat, carrier, getter)
      extractedChild <- createChildOf(extractedSpan, spanName, spanKind)
      r              <- finalizeSpanUsingEffect(effect, extractedChild)
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
      root <- createRoot(spanName, spanKind)
      r    <- finalizeSpanUsingEffect(effect, root)
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
      child <- createChildOf(old, spanName, spanKind)
      r     <- finalizeSpanUsingEffect(effect, child)
    } yield r

  /**
   * Injects the current span into carrier `C`
   */
  def injectCurrentSpan[C](
    httpTextFormat: HttpTextFormat,
    carrier: C,
    setter: HttpTextFormat.Setter[C]
  ): RIO[Tracing, Unit] =
    for {
      current <- getCurrentSpan
      _       <- injectSpan(current, httpTextFormat, carrier, setter)
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
    class Live(defaultSpan: FiberRef[Span], clock: Clock.Service) extends Service {
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
        } yield span

      def createChildOf(parent: Span, spanName: String, spanKind: Span.Kind): UIO[Span] =
        for {
          nanoSeconds <- clock.currentTime(TimeUnit.NANOSECONDS)
          span <- UIO(
                   tracer
                     .spanBuilder(spanName)
                     .setParent(parent)
                     .setSpanKind(spanKind)
                     .setStartTimestamp(nanoSeconds)
                     .startSpan()
                 )
        } yield span

      val currentSpan: FiberRef[Span] = defaultSpan
    }

    val tracing: URIO[Clock, Service] =
      for {
        clock       <- ZIO.access[Clock](_.get)
        defaultSpan <- FiberRef.make[Span](DefaultSpan.getInvalid)
      } yield new Live(defaultSpan, clock)

    ZLayer.fromAcquireRelease(tracing)(_.currentSpan.get.flatMap(endSpan))
  }
}
