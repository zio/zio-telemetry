package zio.telemetry.opentelemetry

import java.util.concurrent.TimeUnit

import scala.concurrent.{ ExecutionContext }

import io.opentelemetry.common.Attributes
import io.opentelemetry.context.propagation.HttpTextFormat
import io.opentelemetry.trace.{ DefaultSpan, EndSpanOptions, Span, Status, Tracer }
import zio.clock.Clock
import zio.telemetry.opentelemetry.attributevalue.AttributeValueConverter.toAttributeValue
import zio.telemetry.opentelemetry.SpanPropagation.{ extractSpan, injectSpan }
import zio._
import zio.telemetry.opentelemetry.attributevalue.AttributeValueConverter
import io.opentelemetry.trace.SpanContext

object Tracing {
  trait Service {
    private[opentelemetry] def currentNanos: UIO[Long]
    private[opentelemetry] val currentSpan: FiberRef[Span]
    private[opentelemetry] def createRoot(spanName: String, spanKind: Span.Kind): UManaged[Span]
    private[opentelemetry] def createChildOf(parent: Span, spanName: String, spanKind: Span.Kind): UManaged[Span]
    private[opentelemetry] def getTracer: UIO[Tracer]
  }

  private def currentNanos: URIO[Tracing, Long] =
    ZIO.accessM[Tracing](_.get.currentNanos)

  private def currentSpan: URIO[Tracing, FiberRef[Span]] =
    ZIO.access[Tracing](_.get.currentSpan)

  private def createRoot(spanName: String, spanKind: Span.Kind): URManaged[Tracing, Span] =
    ZManaged.accessManaged[Tracing](_.get.createRoot(spanName, spanKind))

  private def createChildOf(parent: Span, spanName: String, spanKind: Span.Kind): URManaged[Tracing, Span] =
    ZManaged.accessManaged[Tracing](_.get.createChildOf(parent, spanName, spanKind))

  private def getCurrentSpan: URIO[Tracing, Span] = currentSpan.flatMap(_.get)

  private def getTracer: URIO[Tracing, Tracer] =
    ZIO.access[Tracing](_.get).flatMap(_.getTracer)

  private def toEndTimestamp(time: Long): EndSpanOptions = EndSpanOptions.builder().setEndTimestamp(time).build()

  private def setErrorStatus[E](span: Span, cause: Cause[E], toErrorStatus: PartialFunction[E, Status]): UIO[Unit] = {
    val errorStatus: Status = cause.failureOption.flatMap(toErrorStatus.lift).getOrElse(Status.UNKNOWN)
    UIO(span.setStatus(errorStatus.withDescription(cause.prettyPrint)))
  }

  def spanContext: URIO[Tracing, SpanContext] = getCurrentSpan.map(_.getContext())

  /**
   * Sets the `currentSpan` to `span` only while `effect` runs,
   * and error status of `span` according to any potential failure of effect.
   */
  private def finalizeSpanUsingEffect[R, E, A](
    effect: ZIO[R, E, A],
    span: Span,
    toErrorStatus: PartialFunction[E, Status]
  ): ZIO[R with Tracing, E, A] =
    for {
      current <- currentSpan
      r <- current
            .locally(span)(effect)
            .tapCause(setErrorStatus(span, _, toErrorStatus))
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
    spanKind: Span.Kind,
    toErrorStatus: PartialFunction[E, Status]
  )(effect: ZIO[R, E, A]): ZIO[R with Tracing, E, A] =
    for {
      extractedSpan <- extractSpan(httpTextFormat, carrier, getter)
      r             <- createChildOf(extractedSpan, spanName, spanKind).use(finalizeSpanUsingEffect(effect, _, toErrorStatus))
    } yield r

  /**
   * Sets the current span to be the new root span with name 'spanName'
   * Ends the span when the effect finishes.
   */
  def root[R, E, A](
    spanName: String,
    spanKind: Span.Kind,
    toErrorStatus: PartialFunction[E, Status]
  )(effect: ZIO[R, E, A]): ZIO[R with Tracing, E, A] =
    createRoot(spanName, spanKind).use(finalizeSpanUsingEffect(effect, _, toErrorStatus))

  /**
   * Sets the current span to be the child of the current span with name 'spanName'
   * Ends the span when the effect finishes.
   */
  def span[R, E, A](
    spanName: String,
    spanKind: Span.Kind,
    toErrorStatus: PartialFunction[E, Status]
  )(effect: ZIO[R, E, A]): ZIO[R with Tracing, E, A] =
    for {
      old <- getCurrentSpan
      r   <- createChildOf(old, spanName, spanKind).use(finalizeSpanUsingEffect(effect, _, toErrorStatus))
    } yield r

  /*
   * Introduces a thread-local scope during the execution allowing
   * for non-zio context propagation.
   *
   * Closes the scope when the effect finishes.
   */
  def scopedEffect[R, A](effect: => A): ZIO[Tracing, Throwable, A] =
    for {
      currentSpan <- getCurrentSpan
      tracer      <- getTracer
      eff <- Task.effect {
              val scope = tracer.withSpan(currentSpan)
              try effect
              finally scope.close()
            }
    } yield eff

  /*
   * Introduces a thread-local scope during the execution allowing
   * for non-zio context propagation.
   *
   * Closes the scope when the effect finishes.
   */
  def scopedEffectTotal[R, A](effect: => A): ZIO[Tracing, Nothing, A] =
    for {
      currentSpan <- getCurrentSpan
      tracer      <- getTracer
      eff <- Task.effectTotal {
              val scope = tracer.withSpan(currentSpan)
              try effect
              finally scope.close()
            }
    } yield eff

  /*
   * Introduces a thread-local scope from the currently active zio span
   * allowing for non-zio context propagation. This scope will only be
   * active during Future creation, so another mechanism must be used to
   * ensure that the scope is passed into the Future callbacks.
   *
   * The java auto instrumentation package provides such a mechanism out of
   * the box, so one is not provided as a part of this method.
   *
   * CLoses the scope when the effect finishes
   */
  def scopedEffectFromFuture[R, A](make: ExecutionContext => scala.concurrent.Future[A]): ZIO[Tracing, Throwable, A] =
    for {
      currentSpan <- getCurrentSpan
      tracer      <- getTracer
      eff <- ZIO.fromFuture { implicit ec =>
              val scope = tracer.withSpan(currentSpan)
              try make(ec)
              finally scope.close()
            }
    } yield eff

  /**
   * Injects the current span into carrier `C`
   */
  def inject[C](
    httpTextFormat: HttpTextFormat,
    carrier: C,
    setter: HttpTextFormat.Setter[C]
  ): URIO[Tracing, Unit] =
    for {
      current <- getCurrentSpan
      _       <- injectSpan(current, httpTextFormat, carrier, setter)
    } yield ()

  /**
   * Adds an event to the current span
   */
  def addEvent(name: String): URIO[Tracing, Unit] =
    for {
      nanoSeconds <- currentNanos
      span        <- getCurrentSpan
    } yield span.addEvent(name, nanoSeconds)

  /**
   * Adds an event with attributes to the current span.
   */
  def addEventWithAttributes(
    name: String,
    attributes: Attributes
  ): URIO[Tracing, Unit] =
    for {
      nanoSeconds <- currentNanos
      span        <- getCurrentSpan
    } yield span.addEvent(name, attributes, nanoSeconds)

  /**
   * Sets an attribute of the current span.
   */
  def setAttribute[A: AttributeValueConverter](name: String, value: A): URIO[Tracing, Unit] =
    getCurrentSpan.map(_.setAttribute(name, toAttributeValue(value)))

  def managed(tracer: Tracer): URManaged[Clock, Service] = {
    class Live(defaultSpan: FiberRef[Span], clock: Clock.Service) extends Service {
      def end(span: Span): UIO[Unit] = currentNanos.map(toEndTimestamp _ andThen span.end)

      def currentNanos: UIO[Long] = clock.currentTime(TimeUnit.NANOSECONDS)

      def createRoot(spanName: String, spanKind: Span.Kind): UManaged[Span] =
        for {
          nanoSeconds <- currentNanos.toManaged_
          span <- ZManaged.make(
                   UIO(
                     tracer
                       .spanBuilder(spanName)
                       .setNoParent()
                       .setSpanKind(spanKind)
                       .setStartTimestamp(nanoSeconds)
                       .startSpan()
                   )
                 )(end)
        } yield span

      def createChildOf(parent: Span, spanName: String, spanKind: Span.Kind): UManaged[Span] =
        for {
          nanoSeconds <- currentNanos.toManaged_
          span <- ZManaged.make(
                   UIO(
                     tracer
                       .spanBuilder(spanName)
                       .setParent(parent)
                       .setSpanKind(spanKind)
                       .setStartTimestamp(nanoSeconds)
                       .startSpan()
                   )
                 )(end)
        } yield span

      override private[opentelemetry] def getTracer: UIO[Tracer] =
        UIO.succeed(tracer)

      val currentSpan: FiberRef[Span] = defaultSpan
    }

    def end(tracing: Tracing.Service): UIO[Unit] =
      for {
        nanos <- tracing.currentNanos
        span  <- tracing.currentSpan.get
      } yield span.end(toEndTimestamp(nanos))

    val tracing: URIO[Clock, Service] =
      for {
        clock       <- ZIO.access[Clock](_.get)
        defaultSpan <- FiberRef.make[Span](DefaultSpan.getInvalid)
      } yield new Live(defaultSpan, clock)

    ZManaged.make(tracing)(end)
  }

  def live: URLayer[Clock with Has[Tracer], Tracing] = ZLayer.fromManaged(
    ZIO.access[Has[Tracer]](_.get).toManaged_.flatMap(managed)
  )
}
