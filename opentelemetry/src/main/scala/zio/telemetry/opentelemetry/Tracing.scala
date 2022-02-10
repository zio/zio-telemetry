package zio.telemetry.opentelemetry

import io.opentelemetry.api.baggage.Baggage

import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext
import io.opentelemetry.api.common.{ AttributeKey, Attributes }
import io.opentelemetry.api.trace.{ Span, SpanKind, StatusCode, Tracer }
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.{ TextMapGetter, TextMapPropagator, TextMapSetter }
import zio.telemetry.opentelemetry.ContextPropagation.{ extractContext, injectContext }
import zio._
import io.opentelemetry.api.trace.SpanContext

import scala.jdk.CollectionConverters._

object Tracing {
  trait Service {
    private[opentelemetry] def currentNanos: UIO[Long]
    private[opentelemetry] val currentContext: FiberRef[Context]
    private[opentelemetry] def createRoot(spanName: String, spanKind: SpanKind): UManaged[Context]
    private[opentelemetry] def createChildOf(parent: Context, spanName: String, spanKind: SpanKind): UManaged[Context]
    private[opentelemetry] def createChildOfUnsafe(parent: Context, spanName: String, spanKind: SpanKind): UIO[Context]
    private[opentelemetry] def getTracer: UIO[Tracer]
    private[opentelemetry] def end: UIO[Any]
  }

  private def currentNanos: URIO[Service, Long] =
    ZIO.serviceWithZIO[Service](_.currentNanos)

  private def currentContext: URIO[Service, FiberRef[Context]] =
    ZIO.service[Service].map(_.currentContext)

  private def createRoot(spanName: String, spanKind: SpanKind): URManaged[Service, Context] =
    ZManaged.service[Service].flatMap(_.createRoot(spanName, spanKind))

  private def createChildOf(parent: Context, spanName: String, spanKind: SpanKind): URManaged[Service, Context] =
    ZManaged.service[Service].flatMap(_.createChildOf(parent, spanName, spanKind))

  private def createChildOfUnsafe(parent: Context, spanName: String, spanKind: SpanKind): URIO[Service, Context] =
    ZIO.serviceWithZIO[Service](_.createChildOfUnsafe(parent, spanName, spanKind))

  private def end: URIO[Service, Any] = ZIO.serviceWithZIO(_.end)

  def getCurrentContext: URIO[Service, Context] = currentContext.flatMap(_.get)

  def getCurrentSpan: URIO[Service, Span] = getCurrentContext.map(Span.fromContext)

  private def setErrorStatus[E](
    span: Span,
    cause: Cause[E],
    toErrorStatus: PartialFunction[E, StatusCode]
  ): UIO[Span] = {
    val errorStatus: StatusCode = cause.failureOption.flatMap(toErrorStatus.lift).getOrElse(StatusCode.UNSET)
    UIO(span.setStatus(errorStatus, cause.prettyPrint))
  }

  /**
   * Sets the `currentContext` to `context` only while `effect` runs, and error status of `span` according to any
   * potential failure of effect.
   */
  private def finalizeSpanUsingEffect[R, E, A](
    effect: ZIO[R, E, A],
    context: Context,
    toErrorStatus: PartialFunction[E, StatusCode]
  ): ZIO[R with Service, E, A] =
    for {
      current <- currentContext
      r       <- current
                   .locally(context)(effect)
                   .tapErrorCause(setErrorStatus(Span.fromContext(context), _, toErrorStatus))
    } yield r

  /**
   * Extracts the span from carrier `C` and set its child span with name 'spanName' as the current span. Ends the span
   * when the effect finishes.
   */
  def spanFrom[C, R, E, A](
    propagator: TextMapPropagator,
    carrier: C,
    getter: TextMapGetter[C],
    spanName: String,
    spanKind: SpanKind,
    toErrorStatus: PartialFunction[E, StatusCode]
  )(effect: ZIO[R, E, A]): ZIO[R with Service, E, A] =
    for {
      context <- extractContext(propagator, carrier, getter)
      r       <- createChildOf(context, spanName, spanKind).use(finalizeSpanUsingEffect(effect, _, toErrorStatus))
    } yield r

  /**
   * Extracts the span from carrier `C` and unsafely set its child span with name 'spanName' as the current span. You
   * need to make sure to call the finalize effect to end the span. Primarily useful for interop.
   */
  def spanFromUnsafe[C](
    propagator: TextMapPropagator,
    carrier: C,
    getter: TextMapGetter[C],
    spanName: String,
    spanKind: SpanKind
  ): URIO[Service, (Span, URIO[Service, Any])] =
    for {
      context <- extractContext(propagator, carrier, getter)
      updated <- createChildOfUnsafe(context, spanName, spanKind)
      ctx     <- currentContext
      old     <- ctx.getAndSet(updated)
      span    <- getCurrentSpan
      finalize = end *> ctx.set(old)
    } yield (span, finalize)

  /**
   * Sets the current span to be the new root span with name 'spanName' Ends the span when the effect finishes.
   */
  def root[R, E, A](
    spanName: String,
    spanKind: SpanKind,
    toErrorStatus: PartialFunction[E, StatusCode]
  )(effect: ZIO[R, E, A]): ZIO[R with Service, E, A] =
    createRoot(spanName, spanKind).use(finalizeSpanUsingEffect(effect, _, toErrorStatus))

  /**
   * Sets the current span to be the child of the current span with name 'spanName' Ends the span when the effect
   * finishes.
   */
  def span[R, E, A](
    spanName: String,
    spanKind: SpanKind,
    toErrorStatus: PartialFunction[E, StatusCode]
  )(effect: ZIO[R, E, A]): ZIO[R with Service, E, A] =
    for {
      old <- getCurrentContext
      r   <- createChildOf(old, spanName, spanKind).use(finalizeSpanUsingEffect(effect, _, toErrorStatus))
    } yield r

  /**
   * Unsafely sets the current span to be the child of the current span with name 'spanName' You need to manually call
   * the finalizer to end the span. Useful for interop.
   */
  def spanUnsafe(
    spanName: String,
    spanKind: SpanKind
  ): URIO[Service, (Span, ZIO[Service, Nothing, Any])] =
    for {
      old     <- getCurrentContext
      updated <- createChildOfUnsafe(old, spanName, spanKind)
      ctx     <- currentContext
      _       <- ctx.set(updated)
      span    <- getCurrentSpan
      finalize = end *> ctx.set(old)
    } yield (span, finalize)

  /**
   * Introduces a thread-local scope during the execution allowing for non-zio context propagation.
   *
   * Closes the scope when the effect finishes.
   */
  def scopedEffect[R, A](effect: => A): ZIO[Service, Throwable, A] =
    for {
      currentContext <- getCurrentContext
      eff            <- Task.attempt {
                          val scope = currentContext.makeCurrent()
                          try effect
                          finally scope.close()
                        }
    } yield eff

  /**
   * Introduces a thread-local scope during the execution allowing for non-zio context propagation.
   *
   * Closes the scope when the effect finishes.
   */
  def scopedEffectTotal[R, A](effect: => A): ZIO[Service, Nothing, A] =
    for {
      currentContext <- getCurrentContext
      eff            <- Task.succeed {
                          val scope = currentContext.makeCurrent()
                          try effect
                          finally scope.close()
                        }
    } yield eff

  /**
   * Introduces a thread-local scope from the currently active zio span allowing for non-zio context propagation. This
   * scope will only be active during Future creation, so another mechanism must be used to ensure that the scope is
   * passed into the Future callbacks.
   *
   * The java auto instrumentation package provides such a mechanism out of the box, so one is not provided as a part of
   * this method.
   *
   * CLoses the scope when the effect finishes
   */
  def scopedEffectFromFuture[R, A](make: ExecutionContext => scala.concurrent.Future[A]): ZIO[Service, Throwable, A] =
    for {
      currentContext <- getCurrentContext
      eff            <- ZIO.fromFuture { implicit ec =>
                          val scope = currentContext.makeCurrent()
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
    setter: TextMapSetter[C]
  ): URIO[Service, Unit] =
    for {
      current <- getCurrentContext
      _       <- injectContext(current, propagator, carrier, setter)
    } yield ()

  /**
   * Create a child of 'span' with name 'spanName' as the current span. Ends the span when the effect finishes.
   */
  def inSpan[R, E, A](
    span: Span,
    spanName: String,
    spanKind: SpanKind,
    toErrorStatus: PartialFunction[E, StatusCode]
  )(effect: ZIO[R, E, A]): ZIO[R with Service, E, A] =
    for {
      r <- createChildOf(
             Context.root().`with`(span),
             spanName,
             spanKind
           ).use(
             finalizeSpanUsingEffect(effect, _, toErrorStatus)
           )
    } yield r

  /**
   * Adds an event to the current span
   */
  def addEvent(name: String): URIO[Service, Span] =
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
  ): URIO[Service, Span] =
    for {
      nanoSeconds <- currentNanos
      span        <- getCurrentSpan
    } yield span.addEvent(name, attributes, nanoSeconds, TimeUnit.NANOSECONDS)

  /**
   * Sets an attribute of the current span.
   */
  def setAttribute(name: String, value: Boolean): URIO[Service, Span] =
    getCurrentSpan.map(_.setAttribute(name, value))

  /**
   * Sets an attribute of the current span.
   */
  def setAttribute(name: String, value: Double): URIO[Service, Span] =
    getCurrentSpan.map(_.setAttribute(name, value))

  /**
   * Sets an attribute of the current span.
   */
  def setAttribute(name: String, value: Long): URIO[Service, Span] =
    getCurrentSpan.map(_.setAttribute(name, value))

  /**
   * Sets an attribute of the current span.
   */
  def setAttribute(name: String, value: String): URIO[Service, Span] =
    getCurrentSpan.map(_.setAttribute(name, value))

  def setAttribute[T](key: AttributeKey[T], value: T): URIO[Service, Span] =
    getCurrentSpan.map(_.setAttribute(key, value))

  def setAttribute(name: String, values: Seq[String]): URIO[Service, Span] = {
    val v = values.asJava
    getCurrentSpan.map(_.setAttribute(AttributeKey.stringArrayKey(name), v))
  }

  def setAttribute(name: String, values: Seq[Boolean])(implicit i1: DummyImplicit): URIO[Service, Span] = {
    val v = values.map(Boolean.box).asJava
    getCurrentSpan.map(_.setAttribute(AttributeKey.booleanArrayKey(name), v))
  }

  def setAttribute(name: String, values: Seq[Long])(implicit
    i1: DummyImplicit,
    i2: DummyImplicit
  ): URIO[Service, Span] = {
    val v = values.map(Long.box).asJava
    getCurrentSpan.map(_.setAttribute(AttributeKey.longArrayKey(name), v))
  }

  def setAttribute(name: String, values: Seq[Double])(implicit
    i1: DummyImplicit,
    i2: DummyImplicit,
    i3: DummyImplicit
  ): URIO[Service, Span] = {
    val v = values.map(Double.box).asJava
    getCurrentSpan.map(_.setAttribute(AttributeKey.doubleArrayKey(name), v))
  }

  /**
   * Sets a baggage entry in the current context
   */
  def setBaggage(name: String, value: String): URIO[Service, Context] =
    for {
      contextRef <- currentContext
      context    <- contextRef.updateAndGet(context =>
                      Baggage.fromContext(context).toBuilder.put(name, value).build().storeInContext(context)
                    )
    } yield context

  /**
   * Gets the baggage from current context
   */
  def getCurrentBaggage: URIO[Service, Baggage] =
    getCurrentContext.map(Baggage.fromContext)

  /**
   * Gets the current SpanContext
   */
  def getCurrentSpanContext: URIO[Service, SpanContext] =
    getCurrentSpan.map(_.getSpanContext())

  def managed(tracer: Tracer): URManaged[Clock, Service] = {
    class Live(defaultContext: FiberRef[Context], clock: Clock) extends Service {
      private def endSpan(span: Span): UIO[Unit] = currentNanos.map(span.end(_, TimeUnit.NANOSECONDS))

      def currentNanos: UIO[Long] = clock.currentTime(TimeUnit.NANOSECONDS)

      def createRoot(spanName: String, spanKind: SpanKind): UManaged[Context] =
        for {
          nanoSeconds <- currentNanos.toManaged
          span        <- ZManaged.acquireReleaseWith(
                           UIO(
                             tracer
                               .spanBuilder(spanName)
                               .setNoParent()
                               .setSpanKind(spanKind)
                               .setStartTimestamp(nanoSeconds, TimeUnit.NANOSECONDS)
                               .startSpan()
                           )
                         )(endSpan)
        } yield span.storeInContext(Context.root())

      def createChildOf(parent: Context, spanName: String, spanKind: SpanKind): UManaged[Context] =
        for {
          nanoSeconds <- currentNanos.toManaged
          span        <- ZManaged.acquireReleaseWith(
                           UIO(
                             tracer
                               .spanBuilder(spanName)
                               .setParent(parent)
                               .setSpanKind(spanKind)
                               .setStartTimestamp(nanoSeconds, TimeUnit.NANOSECONDS)
                               .startSpan()
                           )
                         )(endSpan)
        } yield span.storeInContext(parent)

      def createChildOfUnsafe(parent: Context, spanName: String, spanKind: SpanKind): UIO[Context] =
        for {
          nanoSeconds <- currentNanos
          span        <-
            UIO(
              tracer
                .spanBuilder(spanName)
                .setParent(parent)
                .setSpanKind(spanKind)
                .setStartTimestamp(nanoSeconds, TimeUnit.NANOSECONDS)
                .startSpan()
            )
        } yield span.storeInContext(parent)

      override private[opentelemetry] def end: UIO[Any] =
        for {
          nanos   <- currentNanos
          context <- currentContext.get
          span     = Span.fromContext(context)
        } yield span.end(nanos, TimeUnit.NANOSECONDS)

      override private[opentelemetry] def getTracer: UIO[Tracer] =
        UIO.succeed(tracer)

      val currentContext: FiberRef[Context] = defaultContext
    }

    val tracing: URIO[Clock, Service] =
      for {
        clock          <- ZIO.service[Clock]
        defaultContext <- FiberRef.make[Context](Context.root())
      } yield new Live(defaultContext, clock)

    ZManaged.acquireReleaseWith(tracing)(_.end)
  }

  def live: URLayer[Clock with Tracer, Service] = ZManaged.service[Tracer].flatMap(managed).toLayer
}
