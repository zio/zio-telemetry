package zio.telemetry.opentelemetry

import io.opentelemetry.api.baggage.Baggage
import io.opentelemetry.api.common.{ AttributeKey, Attributes }
import io.opentelemetry.api.trace._
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.{ TextMapGetter, TextMapPropagator, TextMapSetter }
import zio._
import zio.telemetry.opentelemetry.ContextPropagation.{ extractContext, injectContext }

import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters._

trait Tracing {
  private[opentelemetry] def currentNanos: UIO[Long]

  private[opentelemetry] val currentContext: FiberRef[Context]

  private[opentelemetry] def createRoot(spanName: String, spanKind: SpanKind): URIO[Scope, Context]

  private[opentelemetry] def createChildOf(
    parent: Context,
    spanName: String,
    spanKind: SpanKind
  ): URIO[Scope, Context]

  private[opentelemetry] def createChildOfUnsafe(parent: Context, spanName: String, spanKind: SpanKind): UIO[Context]

  private[opentelemetry] def getTracer: UIO[Tracer]

  private[opentelemetry] def end: UIO[Any]
}

object Tracing {
  private def currentNanos: URIO[Tracing, Long] =
    ZIO.serviceWithZIO[Tracing](_.currentNanos)

  private def currentContext: URIO[Tracing, FiberRef[Context]] =
    ZIO.service[Tracing].map(_.currentContext)

  private def createRoot(spanName: String, spanKind: SpanKind): URIO[Scope with Tracing, Context] =
    ZIO.serviceWithZIO[Tracing](_.createRoot(spanName, spanKind))

  private def createChildOf(parent: Context, spanName: String, spanKind: SpanKind): URIO[Scope with Tracing, Context] =
    ZIO.serviceWithZIO[Tracing](_.createChildOf(parent, spanName, spanKind))

  private def createChildOfUnsafe(parent: Context, spanName: String, spanKind: SpanKind): URIO[Tracing, Context] =
    ZIO.serviceWithZIO[Tracing](_.createChildOfUnsafe(parent, spanName, spanKind))

  private def end: URIO[Tracing, Any] = ZIO.serviceWithZIO(_.end)

  def getCurrentContext: URIO[Tracing, Context] = currentContext.flatMap(_.get)

  def getCurrentSpan: URIO[Tracing, Span] = getCurrentContext.map(Span.fromContext)

  private def setErrorStatus[E](
    span: Span,
    cause: Cause[E],
    toErrorStatus: PartialFunction[E, StatusCode]
  ): UIO[Span] = {
    val errorStatus: StatusCode = cause.failureOption.flatMap(toErrorStatus.lift).getOrElse(StatusCode.UNSET)
    ZIO.succeed(span.setStatus(errorStatus, cause.prettyPrint))
  }

  /**
   * Sets the `currentContext` to `context` only while `effect` runs, and error status of `span` according to any
   * potential failure of effect.
   */
  private def finalizeSpanUsingEffect[R, E, A](
    effect: ZIO[R, E, A],
    context: Context,
    toErrorStatus: PartialFunction[E, StatusCode]
  ): ZIO[R with Tracing, E, A] =
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
  )(effect: ZIO[R, E, A]): ZIO[R with Tracing, E, A] =
    ZIO.scoped[R with Tracing] {
      for {
        context <- extractContext(propagator, carrier, getter)
        ctx     <- createChildOf(context, spanName, spanKind)
        r       <- finalizeSpanUsingEffect(effect, ctx, toErrorStatus)
      } yield r
    }

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
  ): URIO[Tracing, (Span, URIO[Tracing, Any])] =
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
  )(effect: ZIO[R, E, A]): ZIO[R with Tracing, E, A] =
    ZIO.scoped[R with Tracing] {
      createRoot(spanName, spanKind).flatMap(finalizeSpanUsingEffect(effect, _, toErrorStatus))
    }

  /**
   * Sets the current span to be the child of the current span with name 'spanName' Ends the span when the effect
   * finishes.
   */
  def span[R, E, A](
    spanName: String,
    spanKind: SpanKind,
    toErrorStatus: PartialFunction[E, StatusCode]
  )(effect: ZIO[R, E, A]): ZIO[R with Tracing, E, A] =
    ZIO.scoped[R with Tracing] {
      for {
        old <- getCurrentContext
        ctx <- createChildOf(old, spanName, spanKind)
        r   <- finalizeSpanUsingEffect(effect, ctx, toErrorStatus)
      } yield r
    }

  /**
   * Unsafely sets the current span to be the child of the current span with name 'spanName' You need to manually call
   * the finalizer to end the span. Useful for interop.
   */
  def spanUnsafe(
    spanName: String,
    spanKind: SpanKind
  ): URIO[Tracing, (Span, ZIO[Tracing, Nothing, Any])] =
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
  def scopedEffect[A](effect: => A): ZIO[Tracing, Throwable, A] =
    for {
      currentContext <- getCurrentContext
      eff            <- ZIO.attempt {
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
  def scopedEffectTotal[A](effect: => A): ZIO[Tracing, Nothing, A] =
    for {
      currentContext <- getCurrentContext
      eff            <- ZIO.succeed {
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
  def scopedEffectFromFuture[A](make: ExecutionContext => scala.concurrent.Future[A]): ZIO[Tracing, Throwable, A] =
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
  ): URIO[Tracing, Unit] =
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
  )(effect: ZIO[R, E, A]): ZIO[R with Tracing, E, A] =
    ZIO.scoped[R with Tracing] {
      createChildOf(Context.root().`with`(span), spanName, spanKind)
        .flatMap(finalizeSpanUsingEffect(effect, _, toErrorStatus))
    }

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

  def setAttribute[T](key: AttributeKey[T], value: T): URIO[Tracing, Span] =
    getCurrentSpan.map(_.setAttribute(key, value))

  def setAttribute(name: String, values: Seq[String]): URIO[Tracing, Span] = {
    val v = values.asJava
    getCurrentSpan.map(_.setAttribute(AttributeKey.stringArrayKey(name), v))
  }

  def setAttribute(name: String, values: Seq[Boolean])(implicit i1: DummyImplicit): URIO[Tracing, Span] = {
    val v = values.map(Boolean.box).asJava
    getCurrentSpan.map(_.setAttribute(AttributeKey.booleanArrayKey(name), v))
  }

  def setAttribute(name: String, values: Seq[Long])(implicit
    i1: DummyImplicit,
    i2: DummyImplicit
  ): URIO[Tracing, Span] = {
    val v = values.map(Long.box).asJava
    getCurrentSpan.map(_.setAttribute(AttributeKey.longArrayKey(name), v))
  }

  def setAttribute(name: String, values: Seq[Double])(implicit
    i1: DummyImplicit,
    i2: DummyImplicit,
    i3: DummyImplicit
  ): URIO[Tracing, Span] = {
    val v = values.map(Double.box).asJava
    getCurrentSpan.map(_.setAttribute(AttributeKey.doubleArrayKey(name), v))
  }

  /**
   * Sets a baggage entry in the current context
   */
  def setBaggage(name: String, value: String): URIO[Tracing, Context] =
    for {
      contextRef <- currentContext
      context    <- contextRef.updateAndGet(context =>
                      Baggage.fromContext(context).toBuilder.put(name, value).build().storeInContext(context)
                    )
    } yield context

  /**
   * Gets the baggage from current context
   */
  def getCurrentBaggage: URIO[Tracing, Baggage] =
    getCurrentContext.map(Baggage.fromContext)

  /**
   * Gets the current SpanContext
   */
  def getCurrentSpanContext: URIO[Tracing, SpanContext] =
    getCurrentSpan.map(_.getSpanContext())

  def scoped(tracer: Tracer): URIO[Scope, Tracing] = {
    class Live(defaultContext: FiberRef[Context]) extends Tracing {
      private def endSpan(span: Span): UIO[Unit] = currentNanos.map(span.end(_, TimeUnit.NANOSECONDS))

      def currentNanos: UIO[Long] = Clock.currentTime(TimeUnit.NANOSECONDS)

      def createRoot(spanName: String, spanKind: SpanKind): URIO[Scope, Context] =
        for {
          nanoSeconds <- currentNanos
          span        <- ZIO.acquireRelease(
                           ZIO.succeed(
                             tracer
                               .spanBuilder(spanName)
                               .setNoParent()
                               .setSpanKind(spanKind)
                               .setStartTimestamp(nanoSeconds, TimeUnit.NANOSECONDS)
                               .startSpan()
                           )
                         )(endSpan)
        } yield span.storeInContext(Context.root())

      def createChildOf(parent: Context, spanName: String, spanKind: SpanKind): URIO[Scope, Context] =
        for {
          nanoSeconds <- currentNanos
          span        <- ZIO.acquireRelease(
                           ZIO.succeed(
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
            ZIO.succeed(
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
        ZIO.succeed(tracer)

      val currentContext: FiberRef[Context] = defaultContext
    }

    val tracing: URIO[Scope, Tracing] =
      FiberRef.make[Context](Context.root()).map(new Live(_))

    ZIO.acquireRelease(tracing)(_.end)
  }

  def live: URLayer[Tracer, Tracing] = ZLayer.scoped(ZIO.service[Tracer].flatMap(scoped))
}
