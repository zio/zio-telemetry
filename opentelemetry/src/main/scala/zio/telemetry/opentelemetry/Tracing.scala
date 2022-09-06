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

  def getCurrentContext: UIO[Context] = currentContext.get

  def getCurrentSpan: UIO[Span] = getCurrentContext.map(Span.fromContext)

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
  )(effect: ZIO[R, E, A]): ZIO[R, E, A] =
    extractContext(propagator, carrier, getter).flatMap { context =>
      ZIO.acquireReleaseWith {
        createChildOf(context, spanName, spanKind)
      } { case (r, _) =>
        r
      } { case (_, ctx) =>
        finalizeSpanUsingEffect(effect, ctx, toErrorStatus)
      }
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
      old     <- currentContext.getAndSet(updated)
      span    <- getCurrentSpan
      finalize = end *> currentContext.set(old)
    } yield (span, finalize)

  /**
   * Sets the current span to be the new root span with name 'spanName' Ends the span when the effect finishes.
   */
  def root[R, E, A](
    spanName: String,
    spanKind: SpanKind,
    toErrorStatus: PartialFunction[E, StatusCode]
  )(effect: ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.acquireReleaseWith {
      createRoot(spanName, spanKind)
    } { case (r, _) =>
      r
    } { case (_, ctx) =>
      finalizeSpanUsingEffect(effect, ctx, toErrorStatus)
    }

  /**
   * Sets the current span to be the child of the current span with name 'spanName' Ends the span when the effect
   * finishes.
   */
  def span[R, E, A](
    spanName: String,
    spanKind: SpanKind,
    toErrorStatus: PartialFunction[E, StatusCode]
  )(effect: ZIO[R, E, A]): ZIO[R, E, A] =
    getCurrentContext.flatMap { old =>
      ZIO.acquireReleaseWith {
        createChildOf(old, spanName, spanKind)
      } { case (r, _) =>
        r
      } { case (_, ctx) =>
        finalizeSpanUsingEffect(effect, ctx, toErrorStatus)
      }
    }

  /**
   * Unsafely sets the current span to be the child of the current span with name 'spanName' You need to manually call
   * the finalizer to end the span. Useful for interop.
   */
  def spanUnsafe(
    spanName: String,
    spanKind: SpanKind
  ): UIO[(Span, UIO[Any])] =
    for {
      old     <- getCurrentContext
      updated <- createChildOfUnsafe(old, spanName, spanKind)
      _       <- currentContext.set(updated)
      span    <- getCurrentSpan
      finalize = end *> currentContext.set(old)
    } yield (span, finalize)

  /**
   * Introduces a thread-local scope during the execution allowing for non-zio context propagation.
   *
   * Closes the scope when the effect finishes.
   */
  def scopedEffect[A](effect: => A): ZIO[Any, Throwable, A] =
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
  def scopedEffectTotal[A](effect: => A): UIO[A] =
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
  def scopedEffectFromFuture[A](make: ExecutionContext => scala.concurrent.Future[A]): ZIO[Any, Throwable, A] =
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
  ): UIO[Unit] =
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
  )(effect: ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.acquireReleaseWith {
      createChildOf(Context.root().`with`(span), spanName, spanKind)
    } { case (r, _) =>
      r
    } { case (_, ctx) =>
      finalizeSpanUsingEffect(effect, ctx, toErrorStatus)
    }

  /**
   * Adds an event to the current span
   */
  def addEvent(name: String): UIO[Span] =
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
  ): UIO[Span] =
    for {
      nanoSeconds <- currentNanos
      span        <- getCurrentSpan
    } yield span.addEvent(name, attributes, nanoSeconds, TimeUnit.NANOSECONDS)

  /**
   * Sets an attribute of the current span.
   */
  def setAttribute(name: String, value: Boolean): UIO[Span] =
    getCurrentSpan.map(_.setAttribute(name, value))

  /**
   * Sets an attribute of the current span.
   */
  def setAttribute(name: String, value: Double): UIO[Span] =
    getCurrentSpan.map(_.setAttribute(name, value))

  /**
   * Sets an attribute of the current span.
   */
  def setAttribute(name: String, value: Long): UIO[Span] =
    getCurrentSpan.map(_.setAttribute(name, value))

  /**
   * Sets an attribute of the current span.
   */
  def setAttribute(name: String, value: String): UIO[Span] =
    getCurrentSpan.map(_.setAttribute(name, value))

  def setAttribute[T](key: AttributeKey[T], value: T): UIO[Span] =
    getCurrentSpan.map(_.setAttribute(key, value))

  def setAttribute(name: String, values: Seq[String]): UIO[Span] = {
    val v = values.asJava
    getCurrentSpan.map(_.setAttribute(AttributeKey.stringArrayKey(name), v))
  }

  def setAttribute(name: String, values: Seq[Boolean])(implicit i1: DummyImplicit): UIO[Span] = {
    val v = values.map(Boolean.box).asJava
    getCurrentSpan.map(_.setAttribute(AttributeKey.booleanArrayKey(name), v))
  }

  def setAttribute(name: String, values: Seq[Long])(implicit
    i1: DummyImplicit,
    i2: DummyImplicit
  ): UIO[Span] = {
    val v = values.map(Long.box).asJava
    getCurrentSpan.map(_.setAttribute(AttributeKey.longArrayKey(name), v))
  }

  def setAttribute(name: String, values: Seq[Double])(implicit
    i1: DummyImplicit,
    i2: DummyImplicit,
    i3: DummyImplicit
  ): UIO[Span] = {
    val v = values.map(Double.box).asJava
    getCurrentSpan.map(_.setAttribute(AttributeKey.doubleArrayKey(name), v))
  }

  /**
   * Sets a baggage entry in the current context
   */
  def setBaggage(name: String, value: String): UIO[Context] =
    currentContext.updateAndGet(context =>
      Baggage.fromContext(context).toBuilder.put(name, value).build().storeInContext(context)
    )

  /**
   * Gets the baggage from current context
   */
  def getCurrentBaggage: UIO[Baggage] =
    getCurrentContext.map(Baggage.fromContext)

  /**
   * Gets the current SpanContext
   */
  def getCurrentSpanContext: UIO[SpanContext] =
    getCurrentSpan.map(_.getSpanContext())

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
  ): ZIO[R, E, A] =
    currentContext
      .locally(context)(effect)
      .tapErrorCause(setErrorStatus(Span.fromContext(context), _, toErrorStatus))

  private[opentelemetry] def currentNanos: UIO[Long]

  private[opentelemetry] val currentContext: FiberRef[Context]

  private[opentelemetry] def createRoot(spanName: String, spanKind: SpanKind): UIO[(UIO[Unit], Context)]

  private[opentelemetry] def createChildOf(
    parent: Context,
    spanName: String,
    spanKind: SpanKind
  ): UIO[(UIO[Unit], Context)]

  private[opentelemetry] def createChildOfUnsafe(parent: Context, spanName: String, spanKind: SpanKind): UIO[Context]

  private[opentelemetry] def getTracer: UIO[Tracer]

  private[opentelemetry] def end: UIO[Any]
}

object Tracing {

  def getCurrentContext: URIO[Tracing, Context] = ZIO.serviceWithZIO(_.getCurrentContext)

  def getCurrentSpan: URIO[Tracing, Span] = ZIO.serviceWithZIO(_.getCurrentSpan)

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
    ZIO.serviceWithZIO[Tracing](_.spanFrom(propagator, carrier, getter, spanName, spanKind, toErrorStatus)(effect))

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
    ZIO.serviceWithZIO[Tracing](_.spanFromUnsafe(propagator, carrier, getter, spanName, spanKind))

  /**
   * Sets the current span to be the new root span with name 'spanName' Ends the span when the effect finishes.
   */
  def root[R, E, A](
    spanName: String,
    spanKind: SpanKind,
    toErrorStatus: PartialFunction[E, StatusCode]
  )(effect: ZIO[R, E, A]): ZIO[R with Tracing, E, A] =
    ZIO.serviceWithZIO[Tracing](_.root(spanName, spanKind, toErrorStatus)(effect))

  /**
   * Sets the current span to be the child of the current span with name 'spanName' Ends the span when the effect
   * finishes.
   */
  def span[R, E, A](
    spanName: String,
    spanKind: SpanKind,
    toErrorStatus: PartialFunction[E, StatusCode]
  )(effect: ZIO[R, E, A]): ZIO[R with Tracing, E, A] =
    ZIO.serviceWithZIO[Tracing](_.span(spanName, spanKind, toErrorStatus)(effect))

  /**
   * Unsafely sets the current span to be the child of the current span with name 'spanName' You need to manually call
   * the finalizer to end the span. Useful for interop.
   */
  def spanUnsafe(
    spanName: String,
    spanKind: SpanKind
  ): URIO[Tracing, (Span, ZIO[Tracing, Nothing, Any])] =
    ZIO.serviceWithZIO[Tracing](_.spanUnsafe(spanName, spanKind))

  /**
   * Introduces a thread-local scope during the execution allowing for non-zio context propagation.
   *
   * Closes the scope when the effect finishes.
   */
  def scopedEffect[A](effect: => A): ZIO[Tracing, Throwable, A] =
    ZIO.serviceWithZIO[Tracing](_.scopedEffect(effect))

  /**
   * Introduces a thread-local scope during the execution allowing for non-zio context propagation.
   *
   * Closes the scope when the effect finishes.
   */
  def scopedEffectTotal[A](effect: => A): ZIO[Tracing, Nothing, A] =
    ZIO.serviceWithZIO[Tracing](_.scopedEffectTotal(effect))

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
    ZIO.serviceWithZIO[Tracing](_.scopedEffectFromFuture(make))

  /**
   * Injects the current span into carrier `C`
   */
  def inject[C](
    propagator: TextMapPropagator,
    carrier: C,
    setter: TextMapSetter[C]
  ): URIO[Tracing, Unit] =
    ZIO.serviceWithZIO[Tracing](_.inject(propagator, carrier, setter))

  /**
   * Create a child of 'span' with name 'spanName' as the current span. Ends the span when the effect finishes.
   */
  def inSpan[R, E, A](
    span: Span,
    spanName: String,
    spanKind: SpanKind,
    toErrorStatus: PartialFunction[E, StatusCode]
  )(effect: ZIO[R, E, A]): ZIO[R with Tracing, E, A] =
    ZIO.serviceWithZIO[Tracing](_.inSpan(span, spanName, spanKind, toErrorStatus)(effect))

  /**
   * Adds an event to the current span
   */
  def addEvent(name: String): URIO[Tracing, Span] =
    ZIO.serviceWithZIO[Tracing](_.addEvent(name))

  /**
   * Adds an event with attributes to the current span.
   */
  def addEventWithAttributes(
    name: String,
    attributes: Attributes
  ): URIO[Tracing, Span] =
    ZIO.serviceWithZIO[Tracing](_.addEventWithAttributes(name, attributes))

  /**
   * Sets an attribute of the current span.
   */
  def setAttribute(name: String, value: Boolean): URIO[Tracing, Span] =
    ZIO.serviceWithZIO[Tracing](_.setAttribute(name, value))

  /**
   * Sets an attribute of the current span.
   */
  def setAttribute(name: String, value: Double): URIO[Tracing, Span] =
    ZIO.serviceWithZIO[Tracing](_.setAttribute(name, value))

  /**
   * Sets an attribute of the current span.
   */
  def setAttribute(name: String, value: Long): URIO[Tracing, Span] =
    ZIO.serviceWithZIO[Tracing](_.setAttribute(name, value))

  /**
   * Sets an attribute of the current span.
   */
  def setAttribute(name: String, value: String): URIO[Tracing, Span] =
    ZIO.serviceWithZIO[Tracing](_.setAttribute(name, value))

  def setAttribute[T](key: AttributeKey[T], value: T): URIO[Tracing, Span] =
    ZIO.serviceWithZIO[Tracing](_.setAttribute(key, value))

  def setAttribute(name: String, values: Seq[String]): URIO[Tracing, Span] =
    ZIO.serviceWithZIO[Tracing](_.setAttribute(name, values))

  def setAttribute(name: String, values: Seq[Boolean])(implicit i1: DummyImplicit): URIO[Tracing, Span] =
    ZIO.serviceWithZIO[Tracing](_.setAttribute(name, values)(i1))

  def setAttribute(name: String, values: Seq[Long])(implicit
    i1: DummyImplicit,
    i2: DummyImplicit
  ): URIO[Tracing, Span] =
    ZIO.serviceWithZIO[Tracing](_.setAttribute(name, values)(i1, i2))

  def setAttribute(name: String, values: Seq[Double])(implicit
    i1: DummyImplicit,
    i2: DummyImplicit,
    i3: DummyImplicit
  ): URIO[Tracing, Span] =
    ZIO.serviceWithZIO[Tracing](_.setAttribute(name, values)(i1, i2, i3))

  /**
   * Sets a baggage entry in the current context
   */
  def setBaggage(name: String, value: String): URIO[Tracing, Context] =
    ZIO.serviceWithZIO[Tracing](_.setBaggage(name, value))

  /**
   * Gets the baggage from current context
   */
  def getCurrentBaggage: URIO[Tracing, Baggage] =
    ZIO.serviceWithZIO[Tracing](_.getCurrentBaggage)

  /**
   * Gets the current SpanContext
   */
  def getCurrentSpanContext: URIO[Tracing, SpanContext] =
    ZIO.serviceWithZIO[Tracing](_.getCurrentSpanContext)

  def scoped(tracer: Tracer): URIO[Scope, Tracing] = {
    class Live(defaultContext: FiberRef[Context]) extends Tracing {
      private def endSpan(span: Span): UIO[Unit] = currentNanos.map(span.end(_, TimeUnit.NANOSECONDS))

      def currentNanos: UIO[Long] = Clock.currentTime(TimeUnit.NANOSECONDS)

      def createRoot(spanName: String, spanKind: SpanKind): UIO[(UIO[Unit], Context)] =
        for {
          nanoSeconds <- currentNanos
          span        <- ZIO.succeed(
                           tracer
                             .spanBuilder(spanName)
                             .setNoParent()
                             .setSpanKind(spanKind)
                             .setStartTimestamp(nanoSeconds, TimeUnit.NANOSECONDS)
                             .startSpan()
                         )
        } yield (endSpan(span), span.storeInContext(Context.root()))

      def createChildOf(parent: Context, spanName: String, spanKind: SpanKind): UIO[(UIO[Unit], Context)] =
        for {
          nanoSeconds <- currentNanos
          span        <- ZIO.succeed(
                           tracer
                             .spanBuilder(spanName)
                             .setParent(parent)
                             .setSpanKind(spanKind)
                             .setStartTimestamp(nanoSeconds, TimeUnit.NANOSECONDS)
                             .startSpan()
                         )
        } yield (endSpan(span), span.storeInContext(parent))

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
