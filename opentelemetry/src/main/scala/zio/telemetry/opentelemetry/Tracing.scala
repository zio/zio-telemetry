package zio.telemetry.opentelemetry

import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.{Span, StatusCode, Tracer}
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapPropagator
import zio.clock.Clock
import zio.telemetry.opentelemetry.SpanPropagation.{extractSpan, injectSpan}
import zio._

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

  private def setErrorStatus[E](span: Span, cause: Cause[E], toErrorStatus: PartialFunction[E, StatusCode]): UIO[Span] = {
    val errorStatus: StatusCode = cause.failureOption.flatMap(toErrorStatus.lift).getOrElse(StatusCode.UNSET)
    UIO(span.setStatus(errorStatus, cause.prettyPrint))
  }

  /**
   * Sets the `currentSpan` to `span` only while `effect` runs,
   * and error status of `span` according to any potential failure of effect.
   */
  private def finalizeSpanUsingEffect[R, E, A](
    effect: ZIO[R, E, A],
    span: Span,
    toErrorStatus: PartialFunction[E, StatusCode]
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
    propagator: TextMapPropagator,
    carrier: C,
    getter: TextMapPropagator.Getter[C],
    spanName: String,
    spanKind: Span.Kind,
    toErrorStatus: PartialFunction[E, StatusCode]
  )(effect: ZIO[R, E, A]): ZIO[R with Tracing, E, A] =
    for {
      extractedSpan <- extractSpan(propagator, carrier, getter)
      r             <- createChildOf(extractedSpan, spanName, spanKind).use(finalizeSpanUsingEffect(effect, _, toErrorStatus))
    } yield r

  /**
   * Sets the current span to be the new root span with name 'spanName'
   * Ends the span when the effect finishes.
   */
  def root[R, E, A](
    spanName: String,
    spanKind: Span.Kind,
    toErrorStatus: PartialFunction[E, StatusCode]
  )(effect: ZIO[R, E, A]): ZIO[R with Tracing, E, A] =
    createRoot(spanName, spanKind).use(finalizeSpanUsingEffect(effect, _, toErrorStatus))

  /**
   * Sets the current span to be the child of the current span with name 'spanName'
   * Ends the span when the effect finishes.
   */
  def span[R, E, A](
    spanName: String,
    spanKind: Span.Kind,
    toErrorStatus: PartialFunction[E, StatusCode]
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
      eff <- Task.effect {
              val scope = currentSpan.makeCurrent()
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
      eff <- Task.effectTotal {
              val scope = currentSpan.makeCurrent()
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
      eff <- ZIO.fromFuture { implicit ec =>
              val scope = currentSpan.makeCurrent()
              try make(ec)
              finally scope.close()
            }
    } yield eff

  /**
   * Injects the current span into carrier `C`
   */
  def inject[C](
    propagator: TextMapPropagator,
    carrier: C,
    setter: TextMapPropagator.Setter[C]
  ): URIO[Tracing, Unit] =
    for {
      current <- getCurrentSpan
      _       <- injectSpan(current, propagator, carrier, setter)
    } yield ()

  /**
   * Adds an event to the current span
   */
  def addEvent(name: String): URIO[Tracing, Span] =
    for {
      nanoSeconds <- currentNanos
      span        <- getCurrentSpan
    } yield span.addEvent(name, nanoSeconds, TimeUnit.NANOSECONDS)

  /**
   * Adds an event with attributes to the current span.
   */
  def addEventWithAttributes(
    name: String,
    attributes: Attributes
  ): URIO[Tracing, Span] =
    for {
      nanoSeconds <- currentNanos
      span        <- getCurrentSpan
    } yield span.addEvent(name, attributes, nanoSeconds, TimeUnit.NANOSECONDS)

  /**
   * Sets an attribute of the current span.
   */
  def setAttribute(name: String, value: Boolean): URIO[Tracing, Span] =
    getCurrentSpan.map(_.setAttribute(name, value))

  /**
   * Sets an attribute of the current span.
   */
  def setAttribute(name: String, value: Double): URIO[Tracing, Span] =
    getCurrentSpan.map(_.setAttribute(name, value))

  /**
   * Sets an attribute of the current span.
   */
  def setAttribute(name: String, value: Long): URIO[Tracing, Span] =
    getCurrentSpan.map(_.setAttribute(name, value))

  /**
   * Sets an attribute of the current span.
   */
  def setAttribute(name: String, value: String): URIO[Tracing, Span] =
    getCurrentSpan.map(_.setAttribute(name, value))

  def managed(tracer: Tracer): URManaged[Clock, Service] = {
    class Live(defaultSpan: FiberRef[Span], clock: Clock.Service) extends Service {
      def end(span: Span): UIO[Unit] = currentNanos.map(span.end(_, TimeUnit.NANOSECONDS))

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
                       .setStartTimestamp(nanoSeconds, TimeUnit.NANOSECONDS)
                       .startSpan()
                   )
                 )(end)
        } yield span

      def createChildOf(parent: Span, spanName: String, spanKind: Span.Kind): UManaged[Span] =
        for {
          nanoSeconds <- currentNanos.toManaged_
          context = parent.storeInContext(Context.root())
          span <- ZManaged.make(
                   UIO(
                     tracer
                       .spanBuilder(spanName)
                       .setParent(context)
                       .setSpanKind(spanKind)
                       .setStartTimestamp(nanoSeconds, TimeUnit.NANOSECONDS)
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
      } yield span.end(nanos, TimeUnit.NANOSECONDS)

    val tracing: URIO[Clock, Service] =
      for {
        clock       <- ZIO.access[Clock](_.get)
        defaultSpan <- FiberRef.make[Span](Span.getInvalid)
      } yield new Live(defaultSpan, clock)

    ZManaged.make(tracing)(end)
  }

  def live: URLayer[Clock with Has[Tracer], Tracing] = ZLayer.fromManaged(
    ZIO.access[Has[Tracer]](_.get).toManaged_.flatMap(managed)
  )
}
