package zio.telemetry.opentelemetry.tracing

import io.opentelemetry.api.common.{AttributeKey, Attributes}
import io.opentelemetry.api.trace._
import io.opentelemetry.context.Context
import zio._
import zio.telemetry.opentelemetry.common.Attribute
import zio.telemetry.opentelemetry.context.{ContextStorage, IncomingContextCarrier, OutgoingContextCarrier}
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters._

/**
 * Creates spans and records their attributes and events. A span is created as a child of the current span, as a root
 * span, as a child of a span extracted from a carrier, or as a child of a span passed in. The current span is held in
 * the `Context` of the [[zio.telemetry.opentelemetry.context.ContextStorage]].
 *
 * Use [[Tracing.live]] or [[zio.telemetry.opentelemetry.OpenTelemetry.tracing]] to create an instance.
 *
 * @see
 *   <a href="https://zio.dev/zio-telemetry/opentelemetry/">ZIO Telemetry OpenTelemetry documentation</a>
 */
trait Tracing { self =>

  /**
   * Adds an event to the current span.
   *
   * @param name
   * @param trace
   * @return
   */
  def addEvent(name: String)(implicit trace: Trace): UIO[Unit]

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
  )(implicit trace: Trace): UIO[Unit]

  /**
   * Extracts the span from carrier `C` and sets its child span with name `spanName` as the current span.
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
   * @param attributes
   *   attributes of the child span
   * @param statusMapper
   *   status mapper
   * @param links
   *   spanContexts of the linked spans
   * @param zio
   *   body of the child span
   * @tparam C
   *   carrier
   * @tparam R
   *   environment of the effect
   * @tparam E
   *   failure type that `statusMapper` maps
   * @tparam A
   *   success type that `statusMapper` maps
   */
  def extractSpan[C, R, E, E1 <: E, A, A1 <: A](
    propagator: TraceContextPropagator,
    carrier: IncomingContextCarrier[C],
    spanName: String,
    spanKind: SpanKind = SpanKind.INTERNAL,
    attributes: Attributes = Attributes.empty(),
    statusMapper: StatusMapper[E, A] = StatusMapper.default,
    links: Seq[SpanContext] = Seq.empty
  )(zio: => ZIO[R, E1, A1])(implicit trace: Trace): ZIO[R, E1, A1]

  /**
   * Extracts the span from carrier `C` and unsafely sets its child span with name `spanName` as the current span.
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
   * @param attributes
   *   attributes of the child span
   * @param links
   *   spanContexts of the linked spans
   * @tparam C
   *   carrier
   * @return
   *   the child span, and an effect that ends the current span and restores the previous `Context` when run
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
   * Gets the current `Context`.
   */
  def getCurrentContextUnsafe(implicit trace: Trace): UIO[Context]

  /**
   * Gets the `SpanContext` of the current span.
   */
  def getCurrentSpanContextUnsafe(implicit trace: Trace): UIO[SpanContext]

  /**
   * Gets the current span.
   */
  def getCurrentSpanUnsafe(implicit trace: Trace): UIO[Span]

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
  def injectSpan[C](
    propagator: TraceContextPropagator,
    carrier: OutgoingContextCarrier[C]
  )(implicit trace: Trace): UIO[Unit]

  /**
   * Marks this effect as the child of an externally provided span. Ends the child span when the effect finishes. The
   * external `span` is not ended by this method.
   *
   * zio-opentelemetry marks the child span as being the child of the external span.
   *
   * This is designed for use-cases where you are incrementally introducing zio & zio-telemetry in a project that
   * already makes use of instrumentation, and you need to interoperate with futures-based code.
   *
   * The caller is solely responsible for managing the external span, including calling `Span.end`.
   *
   * It also could be useful in combination with `extractSpanUnsafe` or `spanUnsafe`:
   * {{{
   * import zio._
   *
   * def unsafeChild(tracing: Tracing): UIO[Unit] =
   *   tracing.spanUnsafe("unsafe-span").flatMap { case (span, end) =>
   *     tracing.inSpan(span, "child-of-unsafe-span")(ZIO.unit).unit *> end.unit
   *   }
   * }}}
   *
   * @param span
   *   externally provided span
   * @param spanName
   *   name of the child span
   * @param spanKind
   *   kind of the child span
   * @param attributes
   *   attributes of the child span
   * @param statusMapper
   *   status mapper
   * @param links
   *   spanContexts of the linked spans
   * @param zio
   *   body of the child span
   * @tparam R
   *   environment of the effect
   * @tparam E
   *   failure type that `statusMapper` maps
   * @tparam A
   *   success type that `statusMapper` maps
   */
  def inSpan[R, E, E1 <: E, A, A1 <: A](
    span: Span,
    spanName: String,
    spanKind: SpanKind = SpanKind.INTERNAL,
    attributes: Attributes = Attributes.empty(),
    statusMapper: StatusMapper[E, A] = StatusMapper.default,
    links: Seq[SpanContext] = Seq.empty
  )(zio: => ZIO[R, E1, A1])(implicit trace: Trace): ZIO[R, E1, A1]

  /**
   * Sets the current span to be the new root span with name `spanName`.
   *
   * Ends the span when the effect finishes.
   *
   * @param spanName
   *   name of the new root span
   * @param spanKind
   *   kind of the new root span
   * @param attributes
   *   attributes of the new root span
   * @param statusMapper
   *   status mapper
   * @param links
   *   spanContexts of the linked spans
   * @param zio
   *   body of the new root span
   * @tparam R
   *   environment of the effect
   * @tparam E
   *   failure type that `statusMapper` maps
   * @tparam A
   *   success type that `statusMapper` maps
   */
  def root[R, E, E1 <: E, A, A1 <: A](
    spanName: String,
    spanKind: SpanKind = SpanKind.INTERNAL,
    attributes: Attributes = Attributes.empty(),
    statusMapper: StatusMapper[E, A] = StatusMapper.default,
    links: Seq[SpanContext] = Seq.empty
  )(zio: => ZIO[R, E1, A1])(implicit trace: Trace): ZIO[R, E1, A1]

  /**
   * Introduces a thread-local scope during the execution allowing for non-zio context propagation.
   *
   * Closes the scope when the effect finishes.
   *
   * @param effect
   *   piece of code to execute in the current context
   * @tparam A
   *   result type of the effect
   */
  def scopedEffect[A](effect: => A)(implicit trace: Trace): Task[A]

  /**
   * Introduces a thread-local scope from the currently active zio span allowing for non-zio context propagation. This
   * scope is only active while `make` runs, so another mechanism must be used to pass the scope into the Future
   * callbacks.
   *
   * The java auto instrumentation package provides such a mechanism out of the box, so one is not provided as a part of
   * this method.
   *
   * Closes the scope when `make` returns.
   *
   * @param make
   *   function that provides a [[scala.concurrent.Future]] for a given [[scala.concurrent.ExecutionContext]], and runs
   *   in the current context
   * @tparam A
   *   result type of the Future
   */
  def scopedEffectFromFuture[A](make: ExecutionContext => scala.concurrent.Future[A])(implicit trace: Trace): Task[A]

  /**
   * Introduces a thread-local scope during the execution allowing for non-zio context propagation. Unlike
   * [[scopedEffect]], an exception thrown by `effect` is reported as a defect, not as a failure.
   *
   * Closes the scope when the effect finishes.
   *
   * @param effect
   *   piece of code to execute in the current context
   * @tparam A
   *   result type of the effect
   */
  def scopedEffectTotal[A](effect: => A)(implicit trace: Trace): UIO[A]

  /**
   * Sets the Boolean attribute `name` of the current span to `value`.
   *
   * @param name
   *   name of the attribute
   * @param value
   *   value of the attribute
   */
  def setAttribute(name: String, value: Boolean)(implicit trace: Trace): UIO[Unit]

  /**
   * Sets the `Double` attribute `name` of the current span to `value`.
   *
   * @param name
   *   name of the attribute
   * @param value
   *   value of the attribute
   */
  def setAttribute(name: String, value: Double)(implicit trace: Trace): UIO[Unit]

  /**
   * Sets the `Long` attribute `name` of the current span to `value`.
   *
   * @param name
   *   name of the attribute
   * @param value
   *   value of the attribute
   */
  def setAttribute(name: String, value: Long)(implicit trace: Trace): UIO[Unit]

  /**
   * Sets the `String` attribute `name` of the current span to `value`.
   *
   * @param name
   *   name of the attribute
   * @param value
   *   value of the attribute
   */
  def setAttribute(name: String, value: String)(implicit trace: Trace): UIO[Unit]

  /**
   * Sets the attribute of the current span with the given key to `value`.
   *
   * @param key
   *   key of the attribute
   * @param value
   *   value of the attribute
   * @tparam T
   *   type of the attribute value
   */
  def setAttribute[T](key: AttributeKey[T], value: T)(implicit trace: Trace): UIO[Unit]

  /**
   * Sets the attribute of the current span from a [[zio.telemetry.opentelemetry.common.Attribute]].
   *
   * @param attribute
   *   convenient Scala wrapper for Java key/value
   */
  def setAttribute[T](attribute: Attribute[T])(implicit trace: Trace): UIO[Unit]

  /**
   * Sets the attribute `name` of the current span to the list of `String` values `values`.
   *
   * @param name
   *   name of the attribute
   * @param values
   *   values of the attribute
   */
  def setAttribute(name: String, values: Seq[String])(implicit trace: Trace): UIO[Unit]

  /**
   * Sets the attribute `name` of the current span to the list of `Boolean` values `values`.
   *
   * @param name
   *   name of the attribute
   * @param values
   *   values of the attribute
   * @param i1
   *   dummy implicit value to disambiguate the method calls
   */
  def setAttribute(name: String, values: Seq[Boolean])(implicit i1: DummyImplicit, trace: Trace): UIO[Unit]

  /**
   * Sets the attribute `name` of the current span to the list of `Long` values `values`.
   *
   * @param name
   *   name of the attribute
   * @param values
   *   values of the attribute
   * @param i1
   *   dummy implicit value to disambiguate the method calls
   * @param i2
   *   dummy implicit value to disambiguate the method calls
   */
  def setAttribute(name: String, values: Seq[Long])(implicit
    i1: DummyImplicit,
    i2: DummyImplicit,
    trace: Trace
  ): UIO[Unit]

  /**
   * Sets the attribute `name` of the current span to the list of `Double` values `values`.
   *
   * @param name
   *   name of the attribute
   * @param values
   *   values of the attribute
   * @param i1
   *   dummy implicit value to disambiguate the method calls
   * @param i2
   *   dummy implicit value to disambiguate the method calls
   * @param i3
   *   dummy implicit value to disambiguate the method calls
   */
  def setAttribute(name: String, values: Seq[Double])(implicit
    i1: DummyImplicit,
    i2: DummyImplicit,
    i3: DummyImplicit,
    trace: Trace
  ): UIO[Unit]

  /**
   * Sets the current span to be the child of the current span with name `spanName`.
   *
   * Ends the span when the effect finishes.
   *
   * @param spanName
   *   name of the child span
   * @param spanKind
   *   kind of the child span
   * @param attributes
   *   attributes of the child span
   * @param statusMapper
   *   status mapper
   * @param links
   *   spanContexts of the linked spans
   * @param zio
   *   body of the child span
   * @tparam R
   *   environment of the effect
   * @tparam E
   *   failure type that `statusMapper` maps
   * @tparam A
   *   success type that `statusMapper` maps
   */
  def span[R, E, E1 <: E, A, A1 <: A](
    spanName: String,
    spanKind: SpanKind = SpanKind.INTERNAL,
    attributes: Attributes = Attributes.empty(),
    statusMapper: StatusMapper[E, A] = StatusMapper.default,
    links: Seq[SpanContext] = Seq.empty
  )(zio: => ZIO[R, E1, A1])(implicit trace: Trace): ZIO[R, E1, A1]

  /**
   * Sets the current span to be the child of the current span with name `spanName`.
   *
   * Ends the span when the scope closes. When the scope closes with a failure, the span status is set using
   * `statusMapper`.
   *
   * @param spanName
   *   name of the child span
   * @param spanKind
   *   kind of the child span
   * @param attributes
   *   attributes of the child span
   * @param statusMapper
   *   status mapper
   * @param links
   *   spanContexts of the linked spans
   */
  def spanScoped(
    spanName: String,
    spanKind: SpanKind = SpanKind.INTERNAL,
    attributes: Attributes = Attributes.empty(),
    statusMapper: StatusMapper[Any, Unit] = StatusMapper.default,
    links: Seq[SpanContext] = Seq.empty
  )(implicit trace: Trace): ZIO[Scope, Nothing, Unit]

  /**
   * Unsafely sets the current span to be the child of the current span with name `spanName`.
   *
   * You need to manually call the finalizer to end the span.
   *
   * Primarily useful for interop.
   *
   * @param spanName
   *   name of the child span
   * @param spanKind
   *   kind of the child span
   * @param attributes
   *   attributes of the child span
   * @param links
   *   spanContexts of the linked spans
   * @return
   *   the child span, and an effect that ends the current span and restores the previous `Context` when run
   */
  def spanUnsafe(
    spanName: String,
    spanKind: SpanKind = SpanKind.INTERNAL,
    attributes: Attributes = Attributes.empty(),
    links: Seq[SpanContext] = Seq.empty
  )(implicit trace: Trace): UIO[(Span, UIO[Any])]

  /**
   * [[ZIOAspect]] versions of the span-creating methods of [[Tracing]], for use with `@@`. Each aspect wraps the effect
   * it is applied to in a span.
   */
  object aspects {

    /**
     * [[ZIOAspect]] version of [[Tracing.extractSpan]]. Apply it with `@@`.
     */
    def extractSpan[C, E1, A1](
      propagator: TraceContextPropagator,
      carrier: IncomingContextCarrier[C],
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      attributes: Attributes = Attributes.empty(),
      statusMapper: StatusMapper[E1, A1] = StatusMapper.default,
      links: Seq[SpanContext] = Seq.empty
    ): ZIOAspect[Nothing, Any, Nothing, E1, Nothing, A1] =
      new ZIOAspect[Nothing, Any, Nothing, E1, Nothing, A1] {
        override def apply[R, E <: E1, A <: A1](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          self.extractSpan(propagator, carrier, spanName, spanKind, attributes, statusMapper, links)(zio)
      }

    /**
     * [[ZIOAspect]] version of [[Tracing.inSpan]]. Apply it with `@@`.
     */
    def inSpan[E1, A1](
      span: Span,
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      attributes: Attributes = Attributes.empty(),
      statusMapper: StatusMapper[E1, A1] = StatusMapper.default,
      links: Seq[SpanContext] = Seq.empty
    ): ZIOAspect[Nothing, Any, Nothing, E1, Nothing, A1] =
      new ZIOAspect[Nothing, Any, Nothing, E1, Nothing, A1] {
        override def apply[R, E <: E1, A <: A1](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          self.inSpan(span, spanName, spanKind, attributes, statusMapper, links)(zio)
      }

    /**
     * [[ZIOAspect]] version of [[Tracing.root]]. Apply it with `@@`.
     */
    def root[E1, A1](
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      attributes: Attributes = Attributes.empty(),
      statusMapper: StatusMapper[E1, A1] = StatusMapper.default,
      links: Seq[SpanContext] = Seq.empty
    ): ZIOAspect[Nothing, Any, Nothing, E1, A1, A1] =
      new ZIOAspect[Nothing, Any, Nothing, E1, A1, A1] {
        override def apply[R, E <: E1, A <: A1](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          self.root(spanName, spanKind, attributes, statusMapper, links)(zio)
      }

    /**
     * [[ZIOAspect]] version of [[Tracing.span]]. Apply it with `@@`.
     */
    def span[E1, A1](
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      attributes: Attributes = Attributes.empty(),
      statusMapper: StatusMapper[E1, A1] = StatusMapper.default,
      links: Seq[SpanContext] = Seq.empty
    ): ZIOAspect[Nothing, Any, Nothing, E1, Nothing, A1] =
      new ZIOAspect[Nothing, Any, Nothing, E1, Nothing, A1] {
        override def apply[R, E <: E1, A <: A1](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          self.span(spanName, spanKind, attributes, statusMapper, links)(zio)
      }

  }

}

/**
 * Constructors for [[Tracing]].
 */
object Tracing {

  /**
   * Creates a [[Tracing]] layer that uses the [[io.opentelemetry.api.trace.Tracer]] and the
   * [[zio.telemetry.opentelemetry.context.ContextStorage]] from the environment. See [[scoped]] for what happens when
   * the layer's scope closes.
   *
   * @param logAnnotated
   *   add ZIO log annotations as attributes of each span that is created when `true`
   */
  def live(logAnnotated: Boolean = false): URLayer[Tracer & ContextStorage, Tracing] =
    ZLayer.scoped {
      for {
        tracer     <- ZIO.service[Tracer]
        ctxStorage <- ZIO.service[ContextStorage]
        tracing    <- scoped(tracer, ctxStorage, logAnnotated)
      } yield tracing
    }

  /**
   * Creates a [[Tracing]] that uses `tracer` and `ctxStorage`. When the scope closes, it ends the span that is current
   * at that time.
   *
   * @param tracer
   *   tracer that creates the spans
   * @param ctxStorage
   *   storage of the current `Context`
   * @param logAnnotated
   *   add ZIO log annotations as attributes of each span that is created when `true`
   */
  def scoped(tracer: Tracer, ctxStorage: ContextStorage, logAnnotated: Boolean = false): URIO[Scope, Tracing] = {
    val acquire =
      ZIO.succeed {
        new Tracing { self =>
          override def getCurrentContextUnsafe(implicit trace: Trace): UIO[Context] =
            ctxStorage.get

          override def getCurrentSpanUnsafe(implicit trace: Trace): UIO[Span] =
            getCurrentContextUnsafe.map(Span.fromContext)

          override def getCurrentSpanContextUnsafe(implicit trace: Trace): UIO[SpanContext] =
            getCurrentSpanUnsafe.map(_.getSpanContext())

          override def extractSpan[C, R, E, E1 <: E, A, A1 <: A](
            propagator: TraceContextPropagator,
            carrier: IncomingContextCarrier[C],
            spanName: String,
            spanKind: SpanKind = SpanKind.INTERNAL,
            attributes: Attributes = Attributes.empty(),
            statusMapper: StatusMapper[E, A] = StatusMapper.default,
            links: Seq[SpanContext] = Seq.empty
          )(zio: => ZIO[R, E1, A1])(implicit trace: Trace): ZIO[R, E1, A1] =
            extractContext(propagator, carrier).flatMap { context =>
              ZIO.acquireReleaseWith {
                createChild(context, spanName, spanKind, attributes, links)
              } { case (endSpan, _) =>
                endSpan
              } { case (_, ctx) =>
                finalizeSpanUsingEffect(zio, ctx, statusMapper)
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
              span       <- getCurrentSpanUnsafe
              finalize    = endCurrentSpan *> ctxStorage.set(oldCtx)
            } yield (span, finalize)

          override def root[R, E, E1 <: E, A, A1 <: A](
            spanName: String,
            spanKind: SpanKind = SpanKind.INTERNAL,
            attributes: Attributes = Attributes.empty(),
            statusMapper: StatusMapper[E, A] = StatusMapper.default,
            links: Seq[SpanContext] = Seq.empty
          )(zio: => ZIO[R, E1, A1])(implicit trace: Trace): ZIO[R, E1, A1] =
            ZIO.acquireReleaseWith {
              createRoot(spanName, spanKind, attributes, links)
            } { case (endSpan, _) =>
              endSpan
            } { case (_, ctx) =>
              finalizeSpanUsingEffect(zio, ctx, statusMapper)
            }

          override def span[R, E, E1 <: E, A, A1 <: A](
            spanName: String,
            spanKind: SpanKind = SpanKind.INTERNAL,
            attributes: Attributes = Attributes.empty(),
            statusMapper: StatusMapper[E, A] = StatusMapper.default,
            links: Seq[SpanContext] = Seq.empty
          )(zio: => ZIO[R, E1, A1])(implicit trace: Trace): ZIO[R, E1, A1] =
            getCurrentContextUnsafe.flatMap { old =>
              ZIO.acquireReleaseWith {
                createChild(old, spanName, spanKind, attributes, links)
              } { case (endSpan, _) =>
                endSpan
              } { case (_, ctx) =>
                finalizeSpanUsingEffect(zio, ctx, statusMapper)
              }
            }

          override def spanScoped(
            spanName: String,
            spanKind: SpanKind,
            attributes: Attributes,
            statusMapper: StatusMapper[Any, Unit] = StatusMapper.default,
            links: Seq[SpanContext]
          )(implicit trace: Trace): ZIO[Scope, Nothing, Unit] =
            getCurrentContextUnsafe.flatMap { old =>
              ZIO.acquireReleaseExit {
                for {
                  childUnsafe <- createChild(old, spanName, spanKind, attributes, links)
                  (_, ctx)     = childUnsafe
                  _           <- ctxStorage.locallyScoped(ctx)
                } yield childUnsafe
              } { case ((endSpan, ctx), exit) =>
                val setStatus = exit match {
                  case Exit.Success(_)     => ZIO.unit
                  case Exit.Failure(cause) => setFailureStatus(Span.fromContext(ctx), cause, statusMapper)
                }

                setStatus *> endSpan
              }.unit
            }

          override def spanUnsafe(
            spanName: String,
            spanKind: SpanKind = SpanKind.INTERNAL,
            attributes: Attributes = Attributes.empty(),
            links: Seq[SpanContext] = Seq.empty
          )(implicit trace: Trace): UIO[(Span, UIO[Any])] =
            for {
              ctx        <- getCurrentContextUnsafe
              updatedCtx <- createChildUnsafe(ctx, spanName, spanKind, attributes, links)
              _          <- ctxStorage.set(updatedCtx)
              span       <- getCurrentSpanUnsafe
              finalize    = endCurrentSpan *> ctxStorage.set(ctx)
            } yield (span, finalize)

          override def scopedEffect[A](effect: => A)(implicit trace: Trace): Task[A] =
            for {
              ctx    <- getCurrentContextUnsafe
              effect <- ZIO.attempt {
                          val scope = ctx.makeCurrent()
                          try effect
                          finally scope.close()
                        }
            } yield effect

          override def scopedEffectTotal[A](effect: => A)(implicit trace: Trace): UIO[A] =
            for {
              ctx    <- getCurrentContextUnsafe
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
              ctx    <- getCurrentContextUnsafe
              effect <- ZIO.fromFuture { implicit ec =>
                          val scope = ctx.makeCurrent()
                          try make(ec)
                          finally scope.close()
                        }
            } yield effect

          override def injectSpan[C](
            propagator: TraceContextPropagator,
            carrier: OutgoingContextCarrier[C]
          )(implicit trace: Trace): UIO[Unit] =
            for {
              ctx <- getCurrentContextUnsafe
              _   <- injectContext(ctx, propagator, carrier)
            } yield ()

          override def inSpan[R, E, E1 <: E, A, A1 <: A](
            span: Span,
            spanName: String,
            spanKind: SpanKind = SpanKind.INTERNAL,
            attributes: Attributes = Attributes.empty(),
            statusMapper: StatusMapper[E, A] = StatusMapper.default,
            links: Seq[SpanContext] = Seq.empty
          )(zio: => ZIO[R, E1, A1])(implicit trace: Trace): ZIO[R, E1, A1] =
            ZIO.acquireReleaseWith {
              createChild(Context.root().`with`(span), spanName, spanKind, attributes, links)
            } { case (endSpan, _) =>
              endSpan
            } { case (_, ctx) =>
              finalizeSpanUsingEffect(zio, ctx, statusMapper)
            }

          override def addEvent(name: String)(implicit trace: Trace): UIO[Unit] =
            for {
              nanos <- currentNanos
              span  <- getCurrentSpanUnsafe
              _     <- ZIO.succeed(span.addEvent(name, nanos, TimeUnit.NANOSECONDS))
            } yield ()

          override def addEventWithAttributes(name: String, attributes: Attributes)(implicit trace: Trace): UIO[Unit] =
            for {
              nanos <- currentNanos
              span  <- getCurrentSpanUnsafe
              _     <- ZIO.succeed(span.addEvent(name, attributes, nanos, TimeUnit.NANOSECONDS))
            } yield ()

          override def setAttribute(name: String, value: Boolean)(implicit trace: Trace): UIO[Unit] =
            getCurrentSpanUnsafe.map(_.setAttribute(name, value)).unit

          override def setAttribute(name: String, value: Double)(implicit trace: Trace): UIO[Unit] =
            getCurrentSpanUnsafe.map(_.setAttribute(name, value)).unit

          override def setAttribute(name: String, value: Long)(implicit trace: Trace): UIO[Unit] =
            getCurrentSpanUnsafe.map(_.setAttribute(name, value)).unit

          override def setAttribute(name: String, value: String)(implicit trace: Trace): UIO[Unit] =
            getCurrentSpanUnsafe.map(_.setAttribute(name, value)).unit

          override def setAttribute[T](key: AttributeKey[T], value: T)(implicit trace: Trace): UIO[Unit] =
            getCurrentSpanUnsafe.map(_.setAttribute(key, value)).unit

          override def setAttribute[T](attribute: Attribute[T])(implicit trace: Trace): UIO[Unit] =
            getCurrentSpanUnsafe.map(_.setAttribute(attribute.key, attribute.value)).unit

          override def setAttribute(name: String, values: Seq[String])(implicit trace: Trace): UIO[Unit] = {
            val v = values.asJava
            getCurrentSpanUnsafe.map(_.setAttribute(AttributeKey.stringArrayKey(name), v)).unit
          }

          override def setAttribute(name: String, values: Seq[Boolean])(implicit
            i1: DummyImplicit,
            trace: Trace
          ): UIO[Unit] = {
            val v = values.map(Boolean.box).asJava
            getCurrentSpanUnsafe.map(_.setAttribute(AttributeKey.booleanArrayKey(name), v)).unit
          }

          override def setAttribute(name: String, values: Seq[Long])(implicit
            i1: DummyImplicit,
            i2: DummyImplicit,
            trace: Trace
          ): UIO[Unit] = {
            val v = values.map(Long.box).asJava
            getCurrentSpanUnsafe.map(_.setAttribute(AttributeKey.longArrayKey(name), v)).unit
          }

          override def setAttribute(name: String, values: Seq[Double])(implicit
            i1: DummyImplicit,
            i2: DummyImplicit,
            i3: DummyImplicit,
            trace: Trace
          ): UIO[Unit] = {
            val v = values.map(Double.box).asJava
            getCurrentSpanUnsafe.map(_.setAttribute(AttributeKey.doubleArrayKey(name), v)).unit
          }

          private def setSuccessStatus[E, A](span: Span, a: A, statusMapper: StatusMapper[E, A]): UIO[Span] =
            statusMapper.success
              .lift(a)
              .fold(ZIO.succeed(span)) { case StatusMapper.Result(statusCode, maybeError) =>
                if (statusCode == StatusCode.ERROR)
                  maybeError.fold(ZIO.succeed(span.setStatus(statusCode)))(errorMessage =>
                    ZIO.succeed(span.setStatus(statusCode, errorMessage))
                  )
                else
                  ZIO.succeed(span.setStatus(statusCode))
              }

          private def setFailureStatus[E, A](
            span: Span,
            cause: Cause[E],
            statusMapper: StatusMapper[E, A]
          )(implicit trace: Trace): UIO[Span] = {
            val result =
              cause.failureOption
                .flatMap(statusMapper.failure.lift)
                .getOrElse(StatusMapper.Result(StatusCode.ERROR, None))

            for {
              _          <- if (result.statusCode == StatusCode.ERROR)
                              ZIO.succeed(span.setStatus(result.statusCode, cause.prettyPrint))
                            else
                              ZIO.succeed(span.setStatus(result.statusCode))
              spanResult <- result.error.fold(ZIO.succeed(span))(error => ZIO.succeed(span.recordException(error)))
            } yield spanResult
          }

          /**
           * Sets the `currentContext` to `context` only while `effect` runs, and error status of `span` according to
           * any potential failure of effect.
           */
          private def finalizeSpanUsingEffect[R, E, A](
            zio: ZIO[R, E, A],
            ctx: Context,
            statusMapper: StatusMapper[E, A]
          )(implicit trace: Trace): ZIO[R, E, A] =
            ctxStorage
              .locally(ctx)(zio)
              .tapErrorCause(setFailureStatus(Span.fromContext(ctx), _, statusMapper))
              .tap(setSuccessStatus(Span.fromContext(ctx), _, statusMapper))

          private def currentNanos(implicit trace: Trace): UIO[Long] =
            Clock.currentTime(TimeUnit.NANOSECONDS)

          private def createRoot(
            spanName: String,
            spanKind: SpanKind,
            attributes: Attributes,
            links: Seq[SpanContext]
          )(implicit trace: Trace): UIO[(UIO[Unit], Context)] =
            for {
              nanos         <- currentNanos
              allAttributes <- injectLogAnnotations(attributes)
              span          <- ZIO.succeed(
                                 tracer
                                   .spanBuilder(spanName)
                                   .setNoParent()
                                   .setAllAttributes(allAttributes)
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
              nanos         <- currentNanos
              allAttributes <- injectLogAnnotations(attributes)
              span          <- ZIO.succeed(
                                 tracer
                                   .spanBuilder(spanName)
                                   .setParent(parentCtx)
                                   .setAllAttributes(allAttributes)
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
              nanos         <- currentNanos
              allAttributes <- injectLogAnnotations(attributes)
              span          <-
                ZIO.succeed(
                  tracer
                    .spanBuilder(spanName)
                    .setParent(parentCtx)
                    .setAllAttributes(allAttributes)
                    .setSpanKind(spanKind)
                    .setStartTimestamp(nanos, TimeUnit.NANOSECONDS)
                    .addLinks(links)
                    .startSpan()
                )
            } yield parentCtx.`with`(span)

          private def endSpan(span: Span)(implicit trace: Trace): UIO[Unit] =
            currentNanos.flatMap(nanos => ZIO.succeed(span.end(nanos, TimeUnit.NANOSECONDS)))

          private def endCurrentSpan(implicit trace: Trace): UIO[Any] =
            getCurrentSpanUnsafe.flatMap(endSpan)

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

          private def injectLogAnnotations(attributes: Attributes): UIO[Attributes] =
            if (logAnnotated) {
              for {
                annotations <- ZIO.logAnnotations
              } yield annotations
                .foldLeft(Attributes.builder()) { case (builder, (annotationKey, annotationValue)) =>
                  builder.put(annotationKey, annotationValue)
                }
                .putAll(attributes)
                .build()
            } else {
              ZIO.succeed(attributes)
            }
        }
      }

    def release(tracing: Tracing) =
      tracing.getCurrentSpanUnsafe.flatMap(span => ZIO.succeed(span.end()))

    ZIO.acquireRelease(acquire)(release)
  }

}
