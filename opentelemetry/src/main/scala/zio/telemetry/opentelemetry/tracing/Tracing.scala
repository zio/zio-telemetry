package zio.telemetry.opentelemetry.tracing

import io.opentelemetry.api.common.{ AttributeKey, Attributes }
import io.opentelemetry.api.trace._
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.{ TextMapGetter, TextMapPropagator, TextMapSetter }
import zio._

import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters._

trait Tracing { self =>

  /**
   * Gets the current Context
   *
   * @param trace
   * @return
   */
  def getCurrentContext(implicit trace: Trace): UIO[Context]

  /**
   * Gets the current Span.
   *
   * @param trace
   * @return
   */
  def getCurrentSpan(implicit trace: Trace): UIO[Span]

  /**
   * Gets the current SpanContext.
   *
   * @param trace
   * @return
   */
  def getCurrentSpanContext(implicit trace: Trace): UIO[SpanContext]

  /**
   * Extracts the span from carrier `C` and set its child span with name 'spanName' as the current span.
   *
   * Ends the span when the effect finishes.
   *
   * @param propagator
   *   implementation of [[TextMapPropagator]]
   * @param carrier
   *   mutable data from which the parent span is extracted
   * @param getter
   *   implementation of [[TextMapGetter]] which extracts the context from the `carrier`
   * @param spanName
   *   name of the child span
   * @param spanKind
   *   kind of the child span
   * @param toErrorStatus
   *   error mapper
   * @param effect
   *   body of the child span
   * @param trace
   * @tparam C
   *   carrier
   * @tparam R
   * @tparam E
   * @tparam A
   * @return
   */
  def spanFrom[C, R, E, A](
    propagator: TextMapPropagator,
    carrier: C,
    getter: TextMapGetter[C],
    spanName: String,
    spanKind: SpanKind = SpanKind.INTERNAL,
    toErrorStatus: ErrorMapper[E] = ErrorMapper.default[E]
  )(effect: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]

  /**
   * Extracts the span from carrier `C` and unsafely set its child span with name 'spanName' as the current span.
   *
   * You need to make sure to call the finalize effect to end the span.
   *
   * Primarily useful for interop.
   *
   * @param propagator
   *   implementation of [[TextMapPropagator]]
   * @param carrier
   *   mutable data from which the parent span is extracted
   * @param getter
   *   implementation of [[TextMapGetter]] which extracts the context from the `carrier`
   * @param spanName
   *   name of the child span
   * @param spanKind
   *   kind of the child span
   * @param trace
   * @tparam C
   *   carrier
   * @return
   */
  def spanFromUnsafe[C](
    propagator: TextMapPropagator,
    carrier: C,
    getter: TextMapGetter[C],
    spanName: String,
    spanKind: SpanKind = SpanKind.INTERNAL
  )(implicit trace: Trace): UIO[(Span, UIO[Any])]

  /**
   * Sets the current span to be the new root span with name 'spanName'.
   *
   * Ends the span when the effect finishes.
   *
   * @param spanName
   *   name of the new root span
   * @param spanKind
   *   name of the new root span
   * @param toErrorStatus
   *   error mapper
   * @param effect
   *   body of the new root span
   * @param trace
   * @tparam R
   * @tparam E
   * @tparam A
   * @return
   */
  def root[R, E, A](
    spanName: String,
    spanKind: SpanKind = SpanKind.INTERNAL,
    toErrorStatus: ErrorMapper[E] = ErrorMapper.default[E]
  )(effect: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]

  /**
   * Sets the current span to be the child of the current span with name 'spanName'.
   *
   * Ends the span when the effect finishes.
   *
   * @param spanName
   *   name of the child span
   * @param spanKind
   *   kind of the child span
   * @param toErrorStatus
   *   error mapper
   * @param effect
   *   body of the child span
   * @param trace
   * @tparam R
   * @tparam E
   * @tparam A
   * @return
   */
  def span[R, E, A](
    spanName: String,
    spanKind: SpanKind = SpanKind.INTERNAL,
    toErrorStatus: ErrorMapper[E] = ErrorMapper.default[E]
  )(effect: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]

  /**
   * Unsafely sets the current span to be the child of the current span with name 'spanName'.
   *
   * You need to manually call the finalizer to end the span. Useful for interop.
   *
   * @param spanName
   *   name of the child span
   * @param spanKind
   *   kind of the child span
   * @param trace
   * @return
   */
  def spanUnsafe(
    spanName: String,
    spanKind: SpanKind = SpanKind.INTERNAL
  )(implicit trace: Trace): UIO[(Span, UIO[Any])]

  /**
   * Introduces a thread-local scope during the execution allowing for non-zio context propagation.
   *
   * Closes the scope when the effect finishes.
   *
   * @param effect
   *   piece of code to execute in the current context
   * @param trace
   * @tparam A
   * @return
   */
  def scopedEffect[A](effect: => A)(implicit trace: Trace): Task[A]

  /**
   * Introduces a thread-local scope during the execution allowing for non-zio context propagation.
   *
   * Closes the scope when the effect finishes.
   *
   * @param effect
   *   piece of code to execute in the current context
   * @param trace
   * @tparam A
   * @return
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
   * CLoses the scope when the effect finishes.
   *
   * @param make
   *   function for providing a [[Future]] by a given [[ExecutionContext]] to execute in the current context
   * @param trace
   * @tparam A
   * @return
   */
  def scopedEffectFromFuture[A](make: ExecutionContext => scala.concurrent.Future[A])(implicit trace: Trace): Task[A]

  /**
   * Injects the current span into carrier `C`.
   *
   * @param propagator
   *   implementation of [[TextMapPropagator]]
   * @param carrier
   *   mutable data from which the parent span is extracted
   * @param setter
   *   implementation of [[TextMapSetter]] which sets propagated fields into a `carrier`
   * @param trace
   * @tparam C
   *   carrier
   * @return
   */
  def inject[C](
    propagator: TextMapPropagator,
    carrier: C,
    setter: TextMapSetter[C]
  )(implicit trace: Trace): UIO[Unit]

  /**
   */

  /**
   * Mark this effect as the child of an externally provided span. Ends the span when the effect finishes.
   * zio-opentelemetry will mark the span as being the child of the external one.
   *
   * This is designed for use-cases where you are incrementally introducing zio & zio-telemetry in a project that
   * already makes use of instrumentation, and you need to interoperate with futures-based code.
   *
   * The caller is solely responsible for managing the external span, including calling Span.end
   *
   * @param span
   *   externally provided span
   * @param spanName
   *   name of the child span
   * @param spanKind
   *   kind of the child span
   * @param toErrorStatus
   *   error mapper
   * @param effect
   *   body of the child span
   * @param trace
   * @tparam R
   * @tparam E
   * @tparam A
   * @return
   */
  def inSpan[R, E, A](
    span: Span,
    spanName: String,
    spanKind: SpanKind = SpanKind.INTERNAL,
    toErrorStatus: ErrorMapper[E] = ErrorMapper.default[E]
  )(effect: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]

  /**
   * Adds an event to the current span.
   *
   * @param name
   * @param trace
   * @return
   */
  def addEvent(name: String)(implicit trace: Trace): UIO[Span]

  /**
   * Adds an event with attributes to the current span.
   *
   * @param name
   * @param attributes
   *   event attributes
   * @param trace
   * @return
   */
  def addEventWithAttributes(
    name: String,
    attributes: Attributes
  )(implicit trace: Trace): UIO[Span]

  /**
   * Sets an attribute of the current span.
   *
   * @param name
   * @param value
   * @param trace
   * @return
   */
  def setAttribute(name: String, value: Boolean)(implicit trace: Trace): UIO[Span]

  /**
   * Sets an attribute of the current span.
   *
   * @param name
   * @param value
   * @param trace
   * @return
   */
  def setAttribute(name: String, value: Double)(implicit trace: Trace): UIO[Span]

  /**
   * Sets an attribute of the current span.
   *
   * @param name
   * @param value
   * @param trace
   * @return
   */
  def setAttribute(name: String, value: Long)(implicit trace: Trace): UIO[Span]

  /**
   * Sets an attribute of the current span.
   *
   * @param name
   * @param value
   * @param trace
   * @return
   */
  def setAttribute(name: String, value: String)(implicit trace: Trace): UIO[Span]

  /**
   * Sets an attribute of the current span.
   *
   * @param key
   * @param value
   * @param trace
   * @tparam T
   * @return
   */
  def setAttribute[T](key: AttributeKey[T], value: T)(implicit trace: Trace): UIO[Span]

  /**
   * Sets an attribute of the current span.
   *
   * @param name
   * @param values
   * @param trace
   * @return
   */
  def setAttribute(name: String, values: Seq[String])(implicit trace: Trace): UIO[Span]

  /**
   * Sets an attribute of the current span.
   *
   * @param name
   * @param values
   * @param i1
   *   dummy implicit value to disambiguate the method calls
   * @param trace
   * @return
   */
  def setAttribute(name: String, values: Seq[Boolean])(implicit i1: DummyImplicit, trace: Trace): UIO[Span]

  /**
   * Sets an attribute of the current span.
   *
   * @param name
   * @param values
   * @param i1
   *   dummy implicit value to disambiguate the method calls
   * @param i2
   *   dummy implicit value to disambiguate the method calls
   * @param trace
   * @return
   */
  def setAttribute(name: String, values: Seq[Long])(implicit
    i1: DummyImplicit,
    i2: DummyImplicit,
    trace: Trace
  ): UIO[Span]

  /**
   * Sets an attribute of the current span.
   *
   * @param name
   * @param values
   * @param i1
   *   dummy implicit value to disambiguate the method calls
   * @param i2
   *   dummy implicit value to disambiguate the method calls
   * @param i3
   *   dummy implicit value to disambiguate the method calls
   * @param trace
   * @return
   */
  def setAttribute(name: String, values: Seq[Double])(implicit
    i1: DummyImplicit,
    i2: DummyImplicit,
    i3: DummyImplicit,
    trace: Trace
  ): UIO[Span]

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
        new Tracing { self =>
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
          )(effect: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
            extractContext(propagator, carrier, getter).flatMap { context =>
              ZIO.acquireReleaseWith {
                createChildOf(context, spanName, spanKind)
              } { case (endSpan, _) =>
                endSpan
              } { case (_, ctx) =>
                finalizeSpanUsingEffect(effect, ctx, toErrorStatus)
              }
            }

          override def spanFromUnsafe[C](
            propagator: TextMapPropagator,
            carrier: C,
            getter: TextMapGetter[C],
            spanName: String,
            spanKind: SpanKind = SpanKind.INTERNAL
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
          )(effect: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
            ZIO.acquireReleaseWith {
              createRoot(spanName, spanKind)
            } { case (endSpan, _) =>
              endSpan
            } { case (_, ctx) =>
              finalizeSpanUsingEffect(effect, ctx, toErrorStatus)
            }

          override def span[R, E, A](
            spanName: String,
            spanKind: SpanKind = SpanKind.INTERNAL,
            toErrorStatus: ErrorMapper[E] = ErrorMapper.default[E]
          )(effect: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
            getCurrentContext.flatMap { old =>
              ZIO.acquireReleaseWith {
                createChildOf(old, spanName, spanKind)
              } { case (endSpan, _) =>
                endSpan
              } { case (_, ctx) =>
                finalizeSpanUsingEffect(effect, ctx, toErrorStatus)
              }
            }

          override def spanUnsafe(
            spanName: String,
            spanKind: SpanKind = SpanKind.INTERNAL
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
              effect         <- ZIO.attempt {
                                  val scope = currentContext.makeCurrent()
                                  try effect
                                  finally scope.close()
                                }
            } yield effect

          override def scopedEffectTotal[A](effect: => A)(implicit trace: Trace): UIO[A] =
            for {
              currentContext <- getCurrentContext
              effect         <- ZIO.succeed {
                                  val scope = currentContext.makeCurrent()
                                  try effect
                                  finally scope.close()
                                }
            } yield effect

          override def scopedEffectFromFuture[A](
            make: ExecutionContext => scala.concurrent.Future[A]
          )(implicit trace: Trace): Task[A] =
            for {
              currentContext <- getCurrentContext
              effect         <- ZIO.fromFuture { implicit ec =>
                                  val scope = currentContext.makeCurrent()
                                  try make(ec)
                                  finally scope.close()
                                }
            } yield effect

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
          )(effect: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
            ZIO.acquireReleaseWith {
              createChildOf(Context.root().`with`(span), spanName, spanKind)
            } { case (endSpan, _) =>
              endSpan
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

          private def setErrorStatus[E](
            span: Span,
            cause: Cause[E],
            toErrorStatus: ErrorMapper[E]
          )(implicit trace: Trace): UIO[Span] = {
            val errorStatus =
              cause.failureOption
                .flatMap(toErrorStatus.body.lift)
                .getOrElse(StatusCode.UNSET)

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

          private def currentNanos(implicit trace: Trace): UIO[Long] =
            Clock.currentTime(TimeUnit.NANOSECONDS)

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
