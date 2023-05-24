package zio.telemetry.opentelemetry.tracing

import io.opentelemetry.api.common.{AttributeKey, Attributes}
import io.opentelemetry.api.trace._
import io.opentelemetry.context.Context
import zio._
import zio.telemetry.opentelemetry.context.{ContextStorage, IncomingContextCarrier, OutgoingContextCarrier}
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

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
   *   implementation of [[zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator]]
   * @param carrier
   *   mutable data from which the parent span is extracted
   * @param spanName
   *   name of the child span
   * @param spanKind
   *   kind of the child span
   * @param errorMapper
   *   error mapper
   * @param links
   *   spanContexts of the linked Spans.
   * @param zio
   *   body of the child span
   * @param trace
   * @tparam C
   *   carrier
   * @tparam R
   * @tparam E
   * @tparam A
   * @return
   */
  def extractSpan[C, R, E, A](
    propagator: TraceContextPropagator,
    carrier: IncomingContextCarrier[C],
    spanName: String,
    spanKind: SpanKind = SpanKind.INTERNAL,
    attributes: Attributes = Attributes.empty(),
    errorMapper: ErrorMapper[E] = ErrorMapper.default[E],
    links: Seq[SpanContext] = Seq.empty
  )(zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]

  /**
   * Extracts the span from carrier `C` and unsafely set its child span with name 'spanName' as the current span.
   *
   * You need to make sure to call the finalize effect to end the span.
   *
   * Primarily useful for interop.
   *
   * @param propagator
   *   implementation of [[zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator]]
   * @param carrier
   *   mutable data from which the parent span is extracted
   * @param spanName
   *   name of the child span
   * @param spanKind
   *   kind of the child span
   * @param trace
   * @tparam C
   *   carrier
   * @return
   */
  def extractSpanUnsafe[C](
    propagator: TraceContextPropagator,
    carrier: IncomingContextCarrier[C],
    spanName: String,
    spanKind: SpanKind = SpanKind.INTERNAL,
    attributes: Attributes = Attributes.empty(),
    links: Seq[SpanContext] = Seq.empty
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
   * @param errorMapper
   *   error mapper
   * @param links
   *   spanContexts of the linked Spans.
   * @param zio
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
    attributes: Attributes = Attributes.empty(),
    errorMapper: ErrorMapper[E] = ErrorMapper.default[E],
    links: Seq[SpanContext] = Seq.empty
  )(zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]

  /**
   * Sets the current span to be the child of the current span with name 'spanName'.
   *
   * Ends the span when the effect finishes.
   *
   * @param spanName
   *   name of the child span
   * @param spanKind
   *   kind of the child span
   * @param errorMapper
   *   error mapper
   * @param links
   *   spanContexts of the linked Spans.
   * @param zio
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
    attributes: Attributes = Attributes.empty(),
    errorMapper: ErrorMapper[E] = ErrorMapper.default[E],
    links: Seq[SpanContext] = Seq.empty
  )(zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]

  /**
   * Unsafely sets the current span to be the child of the current span with name 'spanName'.
   *
   * You need to manually call the finalizer to end the span.
   *
   * Primarily useful for interop.
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
    spanKind: SpanKind = SpanKind.INTERNAL,
    attributes: Attributes = Attributes.empty(),
    links: Seq[SpanContext] = Seq.empty
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
   *   function for providing a [[scala.concurrent.Future]] by a given [[scala.concurrent.ExecutionContext]] to execute
   *   in the current context
   * @param trace
   * @tparam A
   * @return
   */
  def scopedEffectFromFuture[A](make: ExecutionContext => scala.concurrent.Future[A])(implicit trace: Trace): Task[A]

  /**
   * Injects the current span into carrier `C`.
   *
   * @param propagator
   *   implementation of [[zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator]]
   * @param carrier
   *   mutable data from which the parent span is extracted
   * @param trace
   * @tparam C
   *   carrier
   * @return
   */
  def inject[C](
    propagator: TraceContextPropagator,
    carrier: OutgoingContextCarrier[C]
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
   * It also could be useful in combination with `extractSpanUnsafe` or `spanUnsafe`:
   * {{{
   *   for {
   *     (span, finalize) <- tracing.spanUnsafe("unsafe-span")
   *     // run some logic that would be wrapped in the span
   *     // modify the span
   *     _                <- zio @@ tracing.inSpan(span, "child-of-unsafe-span")
   *   } yield ()
   * }}}
   *
   * @param span
   *   externally provided span
   * @param spanName
   *   name of the child span
   * @param spanKind
   *   kind of the child span
   * @param errorMapper
   *   error mapper
   * @param links
   *   spanContexts of the linked Spans.
   * @param zio
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
    attributes: Attributes = Attributes.empty(),
    errorMapper: ErrorMapper[E] = ErrorMapper.default[E],
    links: Seq[SpanContext] = Seq.empty
  )(zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]

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

    def extractSpan[C, E1](
      propagator: TraceContextPropagator,
      carrier: IncomingContextCarrier[C],
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      attributes: Attributes = Attributes.empty(),
      errorMapper: ErrorMapper[E1] = ErrorMapper.default[E1],
      links: Seq[SpanContext] = Seq.empty
    ): ZIOAspect[Nothing, Any, E1, E1, Nothing, Any] =
      new ZIOAspect[Nothing, Any, E1, E1, Nothing, Any] {
        override def apply[R, E >: E1 <: E1, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          self.extractSpan(propagator, carrier, spanName, spanKind, attributes, errorMapper, links)(zio)
      }

    def root[E1](
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      attributes: Attributes = Attributes.empty(),
      errorMapper: ErrorMapper[E1] = ErrorMapper.default[E1],
      links: Seq[SpanContext] = Seq.empty
    ): ZIOAspect[Nothing, Any, E1, E1, Nothing, Any] =
      new ZIOAspect[Nothing, Any, E1, E1, Nothing, Any] {
        override def apply[R, E >: E1 <: E1, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          self.root(spanName, spanKind, attributes, errorMapper, links)(zio)
      }

    def span[E1](
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      attributes: Attributes = Attributes.empty(),
      errorMapper: ErrorMapper[E1] = ErrorMapper.default[E1],
      links: Seq[SpanContext] = Seq.empty
    ): ZIOAspect[Nothing, Any, E1, E1, Nothing, Any] =
      new ZIOAspect[Nothing, Any, E1, E1, Nothing, Any] {
        override def apply[R, E >: E1 <: E1, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          self.span(spanName, spanKind, attributes, errorMapper, links)(zio)
      }

    def inSpan[E1](
      span: Span,
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      attributes: Attributes = Attributes.empty(),
      errorMapper: ErrorMapper[E1] = ErrorMapper.default[E1],
      links: Seq[SpanContext] = Seq.empty
    ): ZIOAspect[Nothing, Any, E1, E1, Nothing, Any] =
      new ZIOAspect[Nothing, Any, E1, E1, Nothing, Any] {
        override def apply[R, E >: E1 <: E1, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          self.inSpan(span, spanName, spanKind, attributes, errorMapper, links)(zio)
      }

  }

}

object Tracing {

  def live: URLayer[Tracer with ContextStorage, Tracing] =
    ZLayer.scoped {
      for {
        tracer         <- ZIO.service[Tracer]
        contextStorage <- ZIO.service[ContextStorage]
        tracing        <- scoped(tracer, contextStorage)
      } yield tracing
    }

  def scoped(tracer: Tracer, ctxStorage: ContextStorage): URIO[Scope, Tracing] = {
    val acquire =
      ZIO.succeed {
        new Tracing { self =>
          override def getCurrentContext(implicit trace: Trace): UIO[Context] =
            ctxStorage.get

          override def getCurrentSpan(implicit trace: Trace): UIO[Span] =
            getCurrentContext.map(Span.fromContext)

          override def getCurrentSpanContext(implicit trace: Trace): UIO[SpanContext] =
            getCurrentSpan.map(_.getSpanContext())

          override def extractSpan[C, R, E, A](
            propagator: TraceContextPropagator,
            carrier: IncomingContextCarrier[C],
            spanName: String,
            spanKind: SpanKind = SpanKind.INTERNAL,
            attributes: Attributes = Attributes.empty(),
            errorMapper: ErrorMapper[E] = ErrorMapper.default[E],
            links: Seq[SpanContext] = Seq.empty
          )(zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
            extractContext(propagator, carrier).flatMap { context =>
              ZIO.acquireReleaseWith {
                createChild(context, spanName, spanKind, attributes, links)
              } { case (endSpan, _) =>
                endSpan
              } { case (_, ctx) =>
                finalizeSpanUsingEffect(zio, ctx, errorMapper)
              }
            }

          override def extractSpanUnsafe[C](
            propagator: TraceContextPropagator,
            carrier: IncomingContextCarrier[C],
            spanName: String,
            spanKind: SpanKind = SpanKind.INTERNAL,
            attributes: Attributes = Attributes.empty(),
            links: Seq[SpanContext] = Seq.empty
          )(implicit trace: Trace): UIO[(Span, UIO[Any])] =
            for {
              ctx        <- extractContext(propagator, carrier)
              updatedCtx <- createChildUnsafe(ctx, spanName, spanKind, attributes, links)
              oldCtx     <- ctxStorage.getAndSet(updatedCtx)
              span       <- getCurrentSpan
              finalize    = end *> ctxStorage.set(oldCtx)
            } yield (span, finalize)

          override def root[R, E, A](
            spanName: String,
            spanKind: SpanKind = SpanKind.INTERNAL,
            attributes: Attributes = Attributes.empty(),
            errorMapper: ErrorMapper[E] = ErrorMapper.default[E],
            links: Seq[SpanContext] = Seq.empty
          )(zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
            ZIO.acquireReleaseWith {
              createRoot(spanName, spanKind, attributes, links)
            } { case (endSpan, _) =>
              endSpan
            } { case (_, ctx) =>
              finalizeSpanUsingEffect(zio, ctx, errorMapper)
            }

          override def span[R, E, A](
            spanName: String,
            spanKind: SpanKind = SpanKind.INTERNAL,
            attributes: Attributes = Attributes.empty(),
            errorMapper: ErrorMapper[E] = ErrorMapper.default[E],
            links: Seq[SpanContext] = Seq.empty
          )(zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
            getCurrentContext.flatMap { old =>
              ZIO.acquireReleaseWith {
                createChild(old, spanName, spanKind, attributes, links)
              } { case (endSpan, _) =>
                endSpan
              } { case (_, ctx) =>
                finalizeSpanUsingEffect(zio, ctx, errorMapper)
              }
            }

          override def spanUnsafe(
            spanName: String,
            spanKind: SpanKind = SpanKind.INTERNAL,
            attributes: Attributes = Attributes.empty(),
            links: Seq[SpanContext] = Seq.empty
          )(implicit trace: Trace): UIO[(Span, UIO[Any])] =
            for {
              ctx        <- getCurrentContext
              updatedCtx <- createChildUnsafe(ctx, spanName, spanKind, attributes, links)
              _          <- ctxStorage.set(updatedCtx)
              span       <- getCurrentSpan
              finalize    = end *> ctxStorage.set(ctx)
            } yield (span, finalize)

          override def scopedEffect[A](effect: => A)(implicit trace: Trace): Task[A] =
            for {
              ctx    <- getCurrentContext
              effect <- ZIO.attempt {
                          val scope = ctx.makeCurrent()
                          try effect
                          finally scope.close()
                        }
            } yield effect

          override def scopedEffectTotal[A](effect: => A)(implicit trace: Trace): UIO[A] =
            for {
              ctx    <- getCurrentContext
              effect <- ZIO.succeed {
                          val scope = ctx.makeCurrent()
                          try effect
                          finally scope.close()
                        }
            } yield effect

          override def scopedEffectFromFuture[A](
            make: ExecutionContext => scala.concurrent.Future[A]
          )(implicit trace: Trace): Task[A] =
            for {
              ctx    <- getCurrentContext
              effect <- ZIO.fromFuture { implicit ec =>
                          val scope = ctx.makeCurrent()
                          try make(ec)
                          finally scope.close()
                        }
            } yield effect

          override def inject[C](
            propagator: TraceContextPropagator,
            carrier: OutgoingContextCarrier[C]
          )(implicit trace: Trace): UIO[Unit] =
            for {
              ctx <- getCurrentContext
              _   <- injectContext(ctx, propagator, carrier)
            } yield ()

          override def inSpan[R, E, A](
            span: Span,
            spanName: String,
            spanKind: SpanKind = SpanKind.INTERNAL,
            attributes: Attributes = Attributes.empty(),
            errorMapper: ErrorMapper[E] = ErrorMapper.default[E],
            links: Seq[SpanContext] = Seq.empty
          )(zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
            ZIO.acquireReleaseWith {
              createChild(Context.root().`with`(span), spanName, spanKind, attributes, links)
            } { case (endSpan, _) =>
              endSpan
            } { case (_, ctx) =>
              finalizeSpanUsingEffect(zio, ctx, errorMapper)
            }

          override def addEvent(name: String)(implicit trace: Trace): UIO[Span] =
            for {
              nanos <- currentNanos
              span  <- getCurrentSpan
            } yield span.addEvent(name, nanos, TimeUnit.NANOSECONDS)

          override def addEventWithAttributes(
            name: String,
            attributes: Attributes
          )(implicit trace: Trace): UIO[Span] =
            for {
              nanos <- currentNanos
              span  <- getCurrentSpan
            } yield span.addEvent(name, attributes, nanos, TimeUnit.NANOSECONDS)

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
            errorMapper: ErrorMapper[E]
          )(implicit trace: Trace): UIO[Span] = {
            val errorStatus =
              cause.failureOption
                .flatMap(errorMapper.body.lift)
                .getOrElse(StatusCode.UNSET)

            for {
              _      <- ZIO.succeed(span.setStatus(errorStatus, cause.prettyPrint))
              result <- errorMapper.toThrowable.fold(ZIO.succeed(span))(toThrowable =>
                          ZIO.succeed(span.recordException(cause.squashWith(toThrowable)))
                        )
            } yield result
          }

          /**
           * Sets the `currentContext` to `context` only while `effect` runs, and error status of `span` according to
           * any potential failure of effect.
           */
          private def finalizeSpanUsingEffect[R, E, A](
            zio: ZIO[R, E, A],
            ctx: Context,
            errorMapper: ErrorMapper[E]
          )(implicit trace: Trace): ZIO[R, E, A] =
            ctxStorage
              .locally(ctx)(zio)
              .tapErrorCause(setErrorStatus(Span.fromContext(ctx), _, errorMapper))

          private def currentNanos(implicit trace: Trace): UIO[Long] =
            Clock.currentTime(TimeUnit.NANOSECONDS)

          private def createRoot(
            spanName: String,
            spanKind: SpanKind,
            attributes: Attributes,
            links: Seq[SpanContext]
          )(implicit trace: Trace): UIO[(UIO[Unit], Context)] =
            for {
              nanos <- currentNanos
              span  <- ZIO.succeed(
                         tracer
                           .spanBuilder(spanName)
                           .setNoParent()
                           .setAllAttributes(attributes)
                           .setSpanKind(spanKind)
                           .setStartTimestamp(nanos, TimeUnit.NANOSECONDS)
                           .addLinks(links)
                           .startSpan()
                       )
            } yield (endSpan(span), Context.root().`with`(span))

          private def createChild(
            parentCtx: Context,
            spanName: String,
            spanKind: SpanKind,
            attributes: Attributes,
            links: Seq[SpanContext]
          )(implicit trace: Trace): UIO[(UIO[Unit], Context)] =
            for {
              nanos <- currentNanos
              span  <- ZIO.succeed(
                         tracer
                           .spanBuilder(spanName)
                           .setParent(parentCtx)
                           .setAllAttributes(attributes)
                           .setSpanKind(spanKind)
                           .setStartTimestamp(nanos, TimeUnit.NANOSECONDS)
                           .addLinks(links)
                           .startSpan()
                       )
            } yield (endSpan(span), parentCtx.`with`(span))

          private implicit class SpanBuilderOps(spanBuilder: SpanBuilder) {
            def addLinks(links: Seq[SpanContext]): SpanBuilder =
              links.foldLeft(spanBuilder) { case (builder, link) => builder.addLink(link) }
          }

          private def createChildUnsafe(
            parentCtx: Context,
            spanName: String,
            spanKind: SpanKind,
            attributes: Attributes,
            links: Seq[SpanContext]
          )(implicit trace: Trace): UIO[Context] =
            for {
              nanos <- currentNanos
              span  <-
                ZIO.succeed(
                  tracer
                    .spanBuilder(spanName)
                    .setParent(parentCtx)
                    .setAllAttributes(attributes)
                    .setSpanKind(spanKind)
                    .setStartTimestamp(nanos, TimeUnit.NANOSECONDS)
                    .addLinks(links)
                    .startSpan()
                )
            } yield parentCtx.`with`(span)

          private def endSpan(span: Span)(implicit trace: Trace): UIO[Unit] =
            currentNanos.flatMap(nanos => ZIO.succeed(span.end(nanos, TimeUnit.NANOSECONDS)))

          private def end(implicit trace: Trace): UIO[Any] =
            getCurrentSpan.flatMap(endSpan)

          /**
           * Extract and returns the context from carrier `C`.
           */
          private def extractContext[C](
            propagator: TraceContextPropagator,
            carrier: IncomingContextCarrier[C]
          )(implicit trace: Trace): UIO[Context] =
            ZIO.uninterruptible {
              ZIO.succeed(propagator.instance.extract(Context.root(), carrier.kernel, carrier))
            }

          /**
           * Injects the context into carrier `C`.
           */
          private def injectContext[C](
            ctx: Context,
            propagator: TraceContextPropagator,
            carrier: OutgoingContextCarrier[C]
          )(implicit trace: Trace): UIO[Unit] =
            ZIO.succeed(propagator.instance.inject(ctx, carrier.kernel, carrier))

        }
      }

    def release(tracing: Tracing) =
      tracing.getCurrentSpan.flatMap(span => ZIO.succeed(span.end()))

    ZIO.acquireRelease(acquire)(release)
  }

}
