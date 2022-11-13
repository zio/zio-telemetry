package zio.telemetry.opentelemetry

import io.opentelemetry.api.baggage.Baggage
import io.opentelemetry.api.common.{ AttributeKey, Attributes }
import io.opentelemetry.api.trace._
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.{ TextMapGetter, TextMapPropagator, TextMapSetter }
import zio._

import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters._

trait Tracing { self =>

  def getCurrentContext(implicit trace: Trace): UIO[Context]

  def getCurrentSpan(implicit trace: Trace): UIO[Span]

  /**
   * Gets the current SpanContext
   */
  def getCurrentSpanContext(implicit trace: Trace): UIO[SpanContext]

  /**
   * Extracts the span from carrier `C` and set its child span with name 'spanName' as the current span. Ends the span
   * when the effect finishes.
   */
  def spanFrom[C, R, E, A](
    propagator: TextMapPropagator,
    carrier: C,
    getter: TextMapGetter[C],
    spanName: String,
    spanKind: SpanKind = SpanKind.INTERNAL,
    toErrorStatus: ErrorMapper[E] = ErrorMapper.default[E]
  )(effect: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]

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
  )(implicit trace: Trace): UIO[(Span, UIO[Any])]

  /**
   * Sets the current span to be the new root span with name 'spanName'. Ends the span when the effect finishes.
   */
  def root[R, E, A](
    spanName: String,
    spanKind: SpanKind = SpanKind.INTERNAL,
    toErrorStatus: ErrorMapper[E] = ErrorMapper.default[E]
  )(effect: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]

  /**
   * Sets the current span to be the child of the current span with name 'spanName'. Ends the span when the effect
   * finishes.
   */
  def span[R, E, A](
    spanName: String,
    spanKind: SpanKind = SpanKind.INTERNAL,
    toErrorStatus: ErrorMapper[E] = ErrorMapper.default[E]
  )(effect: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]

  /**
   * Unsafely sets the current span to be the child of the current span with name 'spanName'. You need to manually call
   * the finalizer to end the span. Useful for interop.
   */
  def spanUnsafe(
    spanName: String,
    spanKind: SpanKind
  )(implicit trace: Trace): UIO[(Span, UIO[Any])]

  /**
   * Introduces a thread-local scope during the execution allowing for non-zio context propagation.
   *
   * Closes the scope when the effect finishes.
   */
  def scopedEffect[A](effect: => A)(implicit trace: Trace): Task[A]

  /**
   * Introduces a thread-local scope during the execution allowing for non-zio context propagation.
   *
   * Closes the scope when the effect finishes.
   */
  def scopedEffectTotal[A](effect: => A)(implicit trace: Trace): UIO[A]

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
  def scopedEffectFromFuture[A](make: ExecutionContext => scala.concurrent.Future[A])(implicit trace: Trace): Task[A]

  /**
   * Injects the current span into carrier `C`
   */
  def inject[C](
    propagator: TextMapPropagator,
    carrier: C,
    setter: TextMapSetter[C]
  )(implicit trace: Trace): UIO[Unit]

  /**
   * Mark this effect as the child of an externally provided span. Ends the span when the effect finishes.
   * zio-opentelemetry will mark the span as being the child of the external one.
   *
   * This is designed for use-cases where you are incrementally introducing zio & zio-telemetry in a project that
   * already makes use of instrumentation, and you need to interoperate with futures-based code.
   *
   * The caller is solely responsible for managing the external span, including calling Span.end
   */

  def inSpan[R, E, A](
    span: Span,
    spanName: String,
    spanKind: SpanKind = SpanKind.INTERNAL,
    toErrorStatus: ErrorMapper[E] = ErrorMapper.default[E]
  )(effect: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]

  /**
   * Adds an event to the current span
   */
  def addEvent(name: String)(implicit trace: Trace): UIO[Span]

  /**
   * Adds an event with attributes to the current span.
   */
  def addEventWithAttributes(
    name: String,
    attributes: Attributes
  )(implicit trace: Trace): UIO[Span]

  /**
   * Sets an attribute of the current span.
   */
  def setAttribute(name: String, value: Boolean)(implicit trace: Trace): UIO[Span]

  /**
   * Sets an attribute of the current span.
   */
  def setAttribute(name: String, value: Double)(implicit trace: Trace): UIO[Span]

  /**
   * Sets an attribute of the current span.
   */
  def setAttribute(name: String, value: Long)(implicit trace: Trace): UIO[Span]

  /**
   * Sets an attribute of the current span.
   */
  def setAttribute(name: String, value: String)(implicit trace: Trace): UIO[Span]

  /**
   * Sets an attribute of the current span.
   */
  def setAttribute[T](key: AttributeKey[T], value: T)(implicit trace: Trace): UIO[Span]

  /**
   * Sets an attribute of the current span.
   */
  def setAttribute(name: String, values: Seq[String])(implicit trace: Trace): UIO[Span]

  /**
   * Sets an attribute of the current span.
   */
  def setAttribute(name: String, values: Seq[Boolean])(implicit i1: DummyImplicit, trace: Trace): UIO[Span]

  /**
   * Sets an attribute of the current span.
   */
  def setAttribute(name: String, values: Seq[Long])(implicit
    i1: DummyImplicit,
    i2: DummyImplicit,
    trace: Trace
  ): UIO[Span]

  /**
   * Sets an attribute of the current span.
   */
  def setAttribute(name: String, values: Seq[Double])(implicit
    i1: DummyImplicit,
    i2: DummyImplicit,
    i3: DummyImplicit,
    trace: Trace
  ): UIO[Span]

  /**
   * Sets a baggage entry in the current context
   */
  def setBaggage(name: String, value: String)(implicit trace: Trace): UIO[Context]

  /**
   * Gets the baggage from current context
   */
  def getCurrentBaggage(implicit trace: Trace): UIO[Baggage]

  object aspects {

    def spanFrom[C, E1](
      propagator: TextMapPropagator,
      carrier: C,
      getter: TextMapGetter[C],
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      toErrorStatus: ErrorMapper[E1] = ErrorMapper.default[E1]
    ): ZIOAspect[Nothing, Any, E1, E1, Nothing, Any] =
      new ZIOAspect[Nothing, Any, E1, E1, Nothing, Any] {
        override def apply[R, E >: E1 <: E1, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          self.spanFrom(propagator, carrier, getter, spanName, spanKind, toErrorStatus)(zio)
      }

    def root[E1](
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      toErrorStatus: ErrorMapper[E1] = ErrorMapper.default[E1]
    ): ZIOAspect[Nothing, Any, E1, E1, Nothing, Any] =
      new ZIOAspect[Nothing, Any, E1, E1, Nothing, Any] {
        override def apply[R, E >: E1 <: E1, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          self.root(spanName, spanKind, toErrorStatus)(zio)
      }

    def span[E1](
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      toErrorStatus: ErrorMapper[E1] = ErrorMapper.default[E1]
    ): ZIOAspect[Nothing, Any, E1, E1, Nothing, Any] =
      new ZIOAspect[Nothing, Any, E1, E1, Nothing, Any] {
        override def apply[R, E >: E1 <: E1, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          self.span(spanName, spanKind, toErrorStatus)(zio)
      }

    def inSpan[E1](
      span: Span,
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      toErrorStatus: ErrorMapper[E1] = ErrorMapper.default[E1]
    ): ZIOAspect[Nothing, Any, E1, E1, Nothing, Any] =
      new ZIOAspect[Nothing, Any, E1, E1, Nothing, Any] {
        override def apply[R, E >: E1 <: E1, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          self.inSpan(span, spanName, spanKind, toErrorStatus)(zio)
      }

    def addEvent(name: String): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
      new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
        override def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          zio <* self.addEvent(name)
      }

    def addEventWithAttributes(
      name: String,
      attributes: Attributes
    ): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
      new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
        override def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          zio <* self.addEventWithAttributes(name, attributes)
      }

    def setAttribute(name: String, value: Boolean): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
      new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
        override def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          zio <* self.setAttribute(name, value)
      }

    def setAttribute(name: String, value: Double): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
      new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
        override def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          zio <* self.setAttribute(name, value)
      }

    def setAttribute(name: String, value: Long): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
      new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
        override def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          zio <* self.setAttribute(name, value)
      }

    def setAttribute(name: String, value: String): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
      new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
        override def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          zio <* self.setAttribute(name, value)
      }

    def setAttribute[T](key: AttributeKey[T], value: T): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
      new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
        override def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          zio <* self.setAttribute(key, value)
      }

    def setAttribute(name: String, values: Seq[String]): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
      new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
        override def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          zio <* self.setAttribute(name, values)
      }

    def setAttribute(name: String, values: Seq[Boolean])(implicit
      i1: DummyImplicit
    ): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
      new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
        override def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          zio <* self.setAttribute(name, values)
      }

    def setAttribute(name: String, values: Seq[Long])(implicit
      i1: DummyImplicit,
      i2: DummyImplicit
    ): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
      new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
        override def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          zio <* self.setAttribute(name, values)(i1, i2, trace)
      }

    def setAttribute(name: String, values: Seq[Double])(implicit
      i1: DummyImplicit,
      i2: DummyImplicit,
      i3: DummyImplicit
    ): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
      new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
        override def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          zio <* self.setAttribute(name, values)(i1, i2, i3, trace)
      }

    def setBaggage(name: String, value: String): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
      new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
        override def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          zio <* self.setBaggage(name, value)
      }

  }

}

object Tracing {

  def live: URLayer[Tracer, Tracing] =
    ZLayer.scoped(ZIO.service[Tracer].flatMap { tracer =>
      FiberRef
        .make[Context](Context.root())
        .flatMap(ref => scoped(tracer, ContextStorage.fiberRef(ref)))
    })

  /**
   * Tracing context will be bidirectionally propagated between ZIO and non-ZIO code.
   *
   * Since context propagation adds a performance overhead, it is recommended to use [[Tracing.live]] in most cases.
   *
   * [[Tracing.propagating]] should be used in combination with automatic instrumentation via OpenTelemetry JVM agent
   * only.
   */
  def propagating: URLayer[Tracer, Tracing] =
    Runtime.addSupervisor(new PropagatingSupervisor) ++
      ZLayer.scoped(ZIO.service[Tracer].flatMap(scopedPropagating))

  private def scopedPropagating(tracer: Tracer): URIO[Scope, Tracing] =
    scoped(tracer, ContextStorage.threadLocal)

  def scoped(tracer: Tracer, currentContext: ContextStorage): URIO[Scope, Tracing] = {
    val acquire: URIO[Scope, Tracing] =
      ZIO.succeed {
        new Tracing {
          self =>
          override def getCurrentContext(implicit trace: Trace): UIO[Context] =
            currentContext.get

          override def getCurrentSpan(implicit trace: Trace): UIO[Span] =
            getCurrentContext.map(Span.fromContext)

          override def getCurrentSpanContext(implicit trace: Trace): UIO[SpanContext] =
            getCurrentSpan.map(_.getSpanContext())

          override def spanFrom[C, R, E, A](
            propagator: TextMapPropagator,
            carrier: C,
            getter: TextMapGetter[C],
            spanName: String,
            spanKind: SpanKind = SpanKind.INTERNAL,
            toErrorStatus: ErrorMapper[E] = ErrorMapper.default[E]
          )(effect: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
            extractContext(propagator, carrier, getter).flatMap { context =>
              ZIO.acquireReleaseWith {
                createChildOf(context, spanName, spanKind)
              } { case (r, _) =>
                r
              } { case (_, ctx) =>
                finalizeSpanUsingEffect(effect, ctx, toErrorStatus)
              }
            }

          override def spanFromUnsafe[C](
            propagator: TextMapPropagator,
            carrier: C,
            getter: TextMapGetter[C],
            spanName: String,
            spanKind: SpanKind
          )(implicit trace: Trace): UIO[(Span, UIO[Any])] =
            for {
              context <- extractContext(propagator, carrier, getter)
              updated <- createChildOfUnsafe(context, spanName, spanKind)
              old     <- currentContext.getAndSet(updated)
              span    <- getCurrentSpan
              finalize = end *> currentContext.set(old)
            } yield (span, finalize)

          override def root[R, E, A](
            spanName: String,
            spanKind: SpanKind = SpanKind.INTERNAL,
            toErrorStatus: ErrorMapper[E] = ErrorMapper.default[E]
          )(effect: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
            ZIO.acquireReleaseWith {
              createRoot(spanName, spanKind)
            } { case (r, _) =>
              r
            } { case (_, ctx) =>
              finalizeSpanUsingEffect(effect, ctx, toErrorStatus)
            }

          override def span[R, E, A](
            spanName: String,
            spanKind: SpanKind = SpanKind.INTERNAL,
            toErrorStatus: ErrorMapper[E] = ErrorMapper.default[E]
          )(effect: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
            getCurrentContext.flatMap { old =>
              ZIO.acquireReleaseWith {
                createChildOf(old, spanName, spanKind)
              } { case (r, _) =>
                r
              } { case (_, ctx) =>
                finalizeSpanUsingEffect(effect, ctx, toErrorStatus)
              }
            }

          override def spanUnsafe(
            spanName: String,
            spanKind: SpanKind
          )(implicit trace: Trace): UIO[(Span, UIO[Any])] =
            for {
              old     <- getCurrentContext
              updated <- createChildOfUnsafe(old, spanName, spanKind)
              _       <- currentContext.set(updated)
              span    <- getCurrentSpan
              finalize = end *> currentContext.set(old)
            } yield (span, finalize)

          override def scopedEffect[A](effect: => A)(implicit trace: Trace): Task[A] =
            for {
              currentContext <- getCurrentContext
              eff            <- ZIO.attempt {
                                  val scope = currentContext.makeCurrent()
                                  try effect
                                  finally scope.close()
                                }
            } yield eff

          override def scopedEffectTotal[A](effect: => A)(implicit trace: Trace): UIO[A] =
            for {
              currentContext <- getCurrentContext
              eff            <- ZIO.succeed {
                                  val scope = currentContext.makeCurrent()
                                  try effect
                                  finally scope.close()
                                }
            } yield eff

          override def scopedEffectFromFuture[A](
            make: ExecutionContext => scala.concurrent.Future[A]
          )(implicit trace: Trace): Task[A] =
            for {
              currentContext <- getCurrentContext
              eff            <- ZIO.fromFuture { implicit ec =>
                                  val scope = currentContext.makeCurrent()
                                  try make(ec)
                                  finally scope.close()
                                }
            } yield eff

          override def inject[C](
            propagator: TextMapPropagator,
            carrier: C,
            setter: TextMapSetter[C]
          )(implicit trace: Trace): UIO[Unit] =
            for {
              current <- getCurrentContext
              _       <- injectContext(current, propagator, carrier, setter)
            } yield ()

          override def inSpan[R, E, A](
            span: Span,
            spanName: String,
            spanKind: SpanKind = SpanKind.INTERNAL,
            toErrorStatus: ErrorMapper[E] = ErrorMapper.default[E]
          )(effect: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
            ZIO.acquireReleaseWith {
              createChildOf(Context.root().`with`(span), spanName, spanKind)
            } { case (r, _) =>
              r
            } { case (_, ctx) =>
              finalizeSpanUsingEffect(effect, ctx, toErrorStatus)
            }

          override def addEvent(name: String)(implicit trace: Trace): UIO[Span] =
            for {
              nanoSeconds <- currentNanos
              span        <- getCurrentSpan
            } yield span.addEvent(name, nanoSeconds, TimeUnit.NANOSECONDS)

          override def addEventWithAttributes(
            name: String,
            attributes: Attributes
          )(implicit trace: Trace): UIO[Span] =
            for {
              nanoSeconds <- currentNanos
              span        <- getCurrentSpan
            } yield span.addEvent(name, attributes, nanoSeconds, TimeUnit.NANOSECONDS)

          override def setAttribute(name: String, value: Boolean)(implicit trace: Trace): UIO[Span] =
            getCurrentSpan.map(_.setAttribute(name, value))

          override def setAttribute(name: String, value: Double)(implicit trace: Trace): UIO[Span] =
            getCurrentSpan.map(_.setAttribute(name, value))

          override def setAttribute(name: String, value: Long)(implicit trace: Trace): UIO[Span] =
            getCurrentSpan.map(_.setAttribute(name, value))

          override def setAttribute(name: String, value: String)(implicit trace: Trace): UIO[Span] =
            getCurrentSpan.map(_.setAttribute(name, value))

          override def setAttribute[T](key: AttributeKey[T], value: T)(implicit trace: Trace): UIO[Span] =
            getCurrentSpan.map(_.setAttribute(key, value))

          override def setAttribute(name: String, values: Seq[String])(implicit trace: Trace): UIO[Span] = {
            val v = values.asJava
            getCurrentSpan.map(_.setAttribute(AttributeKey.stringArrayKey(name), v))
          }

          override def setAttribute(name: String, values: Seq[Boolean])(implicit
            i1: DummyImplicit,
            trace: Trace
          ): UIO[Span] = {
            val v = values.map(Boolean.box).asJava
            getCurrentSpan.map(_.setAttribute(AttributeKey.booleanArrayKey(name), v))
          }

          override def setAttribute(name: String, values: Seq[Long])(implicit
            i1: DummyImplicit,
            i2: DummyImplicit,
            trace: Trace
          ): UIO[Span] = {
            val v = values.map(Long.box).asJava
            getCurrentSpan.map(_.setAttribute(AttributeKey.longArrayKey(name), v))
          }

          override def setAttribute(name: String, values: Seq[Double])(implicit
            i1: DummyImplicit,
            i2: DummyImplicit,
            i3: DummyImplicit,
            trace: Trace
          ): UIO[Span] = {
            val v = values.map(Double.box).asJava
            getCurrentSpan.map(_.setAttribute(AttributeKey.doubleArrayKey(name), v))
          }

          override def setBaggage(name: String, value: String)(implicit trace: Trace): UIO[Context] =
            currentContext.updateAndGet(context =>
              Baggage.fromContext(context).toBuilder.put(name, value).build().storeInContext(context)
            )

          override def getCurrentBaggage(implicit trace: Trace): UIO[Baggage] =
            getCurrentContext.map(Baggage.fromContext)

          private def setErrorStatus[E](
            span: Span,
            cause: Cause[E],
            toErrorStatus: ErrorMapper[E]
          )(implicit trace: Trace): UIO[Span] = {
            val errorStatus: StatusCode =
              cause.failureOption.flatMap(toErrorStatus.body.lift).getOrElse(StatusCode.UNSET)
            ZIO.succeed(span.setStatus(errorStatus, cause.prettyPrint))
          }

          /**
           * Sets the `currentContext` to `context` only while `effect` runs, and error status of `span` according to
           * any potential failure of effect.
           */
          private def finalizeSpanUsingEffect[R, E, A](
            effect: ZIO[R, E, A],
            context: Context,
            toErrorStatus: ErrorMapper[E]
          )(implicit trace: Trace): ZIO[R, E, A] =
            currentContext
              .locally(context)(effect)
              .tapErrorCause(setErrorStatus(Span.fromContext(context), _, toErrorStatus))

          private def currentNanos(implicit trace: Trace): UIO[Long] = Clock.currentTime(TimeUnit.NANOSECONDS)

          private def createRoot(spanName: String, spanKind: SpanKind)(implicit
            trace: Trace
          ): UIO[(UIO[Unit], Context)] =
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

          private def createChildOf(parent: Context, spanName: String, spanKind: SpanKind)(implicit
            trace: Trace
          ): UIO[(UIO[Unit], Context)] =
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

          private def createChildOfUnsafe(parent: Context, spanName: String, spanKind: SpanKind)(implicit
            trace: Trace
          ): UIO[Context] =
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

          private def endSpan(span: Span)(implicit trace: Trace): UIO[Unit] =
            currentNanos.flatMap(nanos => ZIO.succeed(span.end(nanos, TimeUnit.NANOSECONDS)))

          private def end(implicit trace: Trace): UIO[Any] =
            getCurrentSpan.flatMap(endSpan)

          /**
           * Extract and returns the context from carrier `C`.
           */
          private def extractContext[C](
            propagator: TextMapPropagator,
            carrier: C,
            getter: TextMapGetter[C]
          )(implicit trace: Trace): UIO[Context] =
            ZIO.uninterruptible {
              ZIO.succeed(propagator.extract(Context.root(), carrier, getter))
            }

          /**
           * Injects the context into carrier `C`.
           */
          private def injectContext[C](
            context: Context,
            propagator: TextMapPropagator,
            carrier: C,
            setter: TextMapSetter[C]
          )(implicit trace: Trace): UIO[Unit] =
            ZIO.succeed(propagator.inject(context, carrier, setter))

        }
      }

    def release(tracing: Tracing) =
      tracing.getCurrentSpan.flatMap(span => ZIO.succeed(span.end()))

    ZIO.acquireRelease(acquire)(release)
  }

}
