package zio.telemetry.opentelemetry.trace

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.{Span => JSpan, SpanBuilder, SpanContext, SpanKind, StatusCode, Tracer => JTracer}
import io.opentelemetry.context.Context
import zio._
import zio.telemetry.opentelemetry.context.internal.ContextStorage

import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext

trait Tracer { self =>

  /**
   * Gets the current SpanContext.
   *
   * @param trace
   * @return
   */
  // TODO: remove
  def getCurrentSpanContextUnsafe(implicit trace: Trace): UIO[SpanContext]

  /**
   * Gets the current Span.
   *
   * @param trace
   * @return
   */
  // TODO: remove
  def getCurrentSpanUnsafe(implicit trace: Trace): UIO[JSpan]

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
   *     (span, finalize) <- tracer.spanUnsafe("unsafe-span")
   *     // run some logic that would be wrapped in the span
   *     // modify the span
   *     _                <- zio @@ tracer.inSpan(span, "child-of-unsafe-span")
   *   } yield ()
   * }}}
   *
   * @param span
   *   externally provided span
   * @param spanName
   *   name of the child span
   * @param spanKind
   *   kind of the child span
   * @param statusMapper
   *   status mapper
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
  def inSpan[R, E, E1 <: E, A, A1 <: A](
    span: JSpan,
    spanName: String,
    spanKind: SpanKind = SpanKind.INTERNAL,
    attributes: Attributes = Attributes.empty(),
    statusMapper: StatusMapper[E, A] = StatusMapper.default,
    links: Seq[SpanContext] = Seq.empty
  )(zio: => ZIO[R, E1, A1])(implicit trace: Trace): ZIO[R, E1, A1]

  /**
   * Sets the current span to be the new root span with name 'spanName'.
   *
   * Ends the span when the effect finishes.
   *
   * @param spanName
   *   name of the new root span
   * @param spanKind
   *   name of the new root span
   * @param statusMapper
   *   status mapper
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
   * @param trace
   * @tparam A
   * @return
   */
  def scopedEffect[A](effect: => A)(implicit trace: Trace): Task[A]

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
   * Sets the current span to be the child of the current span with name 'spanName'.
   *
   * Ends the span when the effect finishes.
   *
   * @param spanName
   *   name of the child span
   * @param spanKind
   *   kind of the child span
   * @param statusMapper
   *   status mapper
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
  def span[R, E, E1 <: E, A, A1 <: A](
    spanName: String,
    spanKind: SpanKind = SpanKind.INTERNAL,
    attributes: Attributes = Attributes.empty(),
    statusMapper: StatusMapper[E, A] = StatusMapper.default,
    links: Seq[SpanContext] = Seq.empty
  )(zio: => ZIO[R, E1, A1])(implicit trace: Trace): ZIO[R, E1, A1]

  /**
   * Sets the current span to be the child of the current span with name 'spanName'.
   *
   * Ends the span when the scope closes.
   *
   * @param spanName
   *   name of the child span
   * @param spanKind
   *   kind of the child span
   * @param statusMapper
   *   status mapper
   * @param links
   *   spanContexts of the linked Spans.
   */
  def spanScoped(
    spanName: String,
    spanKind: SpanKind = SpanKind.INTERNAL,
    attributes: Attributes = Attributes.empty(),
    statusMapper: StatusMapper[Any, Unit] = StatusMapper.default,
    links: Seq[SpanContext] = Seq.empty
  )(implicit trace: Trace): ZIO[Scope, Nothing, Unit]

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
    statusMapper: StatusMapper[Any, Unit] = StatusMapper.default,
    links: Seq[SpanContext] = Seq.empty
  )(implicit trace: Trace): ZIO[Scope, Nothing, JSpan]

  object aspects {

    def inSpan[E1, A1](
      span: JSpan,
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

    def root[E1, A1](
      spanName: String,
      spanKind: SpanKind = SpanKind.INTERNAL,
      attributes: Attributes = Attributes.empty(),
      statusMapper: StatusMapper[E1, A1] = StatusMapper.default,
      links: Seq[SpanContext] = Seq.empty
    ): ZIOAspect[Nothing, Any, Nothing, E1, Nothing, A1] =
      new ZIOAspect[Nothing, Any, Nothing, E1, Nothing, A1] {
        override def apply[R, E <: E1, A <: A1](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          self.root(spanName, spanKind, attributes, statusMapper, links)(zio)
      }

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

private[opentelemetry] object Tracer {

  def scoped(tracer: JTracer, ctxStorage: ContextStorage, logAnnotated: Boolean = false): URIO[Scope, Tracer] = {
    val acquire =
      ZIO.succeed {
        new Tracer { self =>
          override def getCurrentSpanUnsafe(implicit trace: Trace): UIO[JSpan] =
            ctxStorage.get.map(JSpan.fromContext)

          override def getCurrentSpanContextUnsafe(implicit trace: Trace): UIO[SpanContext] =
            getCurrentSpanUnsafe.map(_.getSpanContext())

          override def root[R, E, E1 <: E, A, A1 <: A](
            spanName: String,
            spanKind: SpanKind = SpanKind.INTERNAL,
            attributes: Attributes = Attributes.empty(),
            statusMapper: StatusMapper[E, A] = StatusMapper.default,
            links: Seq[SpanContext] = Seq.empty
          )(zio: => ZIO[R, E1, A1])(implicit trace: Trace): ZIO[R, E1, A1] =
            ZIO.acquireReleaseWith {
              startRoot(spanName, spanKind, attributes, links)
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
            ctxStorage.get.flatMap { parentCtx =>
              ZIO.acquireReleaseWith {
                startChild(parentCtx, spanName, spanKind, attributes, links)
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
            ctxStorage.get.flatMap { parentCtx =>
              ZIO.acquireReleaseExit {
                for {
                  childUnsafe <- startChild(parentCtx, spanName, spanKind, attributes, links)
                  (_, ctx)     = childUnsafe
                  _           <- ctxStorage.locallyScoped(ctx)
                } yield childUnsafe
              } { case ((endSpan, ctx), exit) =>
                val setStatus = exit match {
                  case Exit.Success(_)     => ZIO.unit
                  case Exit.Failure(cause) => setFailureStatus(JSpan.fromContext(ctx), cause, statusMapper)
                }

                setStatus *> endSpan
              }.unit
            }

          override def spanUnsafe(
            spanName: String,
            spanKind: SpanKind = SpanKind.INTERNAL,
            attributes: Attributes = Attributes.empty(),
            statusMapper: StatusMapper[Any, Unit] = StatusMapper.default,
            links: Seq[SpanContext] = Seq.empty
          )(implicit trace: Trace): ZIO[Scope, Nothing, JSpan] =
            for {
              parentCtx   <- ctxStorage.get
              scoped      <- ZIO.acquireReleaseExit {
                               for {
                                 childUnsafe   <- startChild(parentCtx, spanName, spanKind, attributes, links)
                                 (endSpan, ctx) = childUnsafe
                                 span           = JSpan.fromContext(ctx)
                                 _             <- ctxStorage.locallyScoped(ctx)
                               } yield (span, endSpan, ctx)
                             } { case ((_, endSpan, ctx), exit) =>
                               val setStatus = exit match {
                                 case Exit.Success(_)     => ZIO.unit
                                 case Exit.Failure(cause) => setFailureStatus(JSpan.fromContext(ctx), cause, statusMapper)
                               }

                               setStatus *> endSpan
                             }
              (span, _, _) = scoped
            } yield span

          override def scopedEffect[A](effect: => A)(implicit trace: Trace): Task[A] =
            for {
              ctx    <- ctxStorage.get
              effect <- ZIO.attempt {
                          val scope = ctx.makeCurrent()
                          try effect
                          finally scope.close()
                        }
            } yield effect

          override def scopedEffectTotal[A](effect: => A)(implicit trace: Trace): UIO[A] =
            for {
              ctx    <- ctxStorage.get
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
              ctx    <- ctxStorage.get
              effect <- ZIO.fromFuture { implicit ec =>
                          val scope = ctx.makeCurrent()
                          try make(ec)
                          finally scope.close()
                        }
            } yield effect

          override def inSpan[R, E, E1 <: E, A, A1 <: A](
            span: JSpan,
            spanName: String,
            spanKind: SpanKind = SpanKind.INTERNAL,
            attributes: Attributes = Attributes.empty(),
            statusMapper: StatusMapper[E, A] = StatusMapper.default,
            links: Seq[SpanContext] = Seq.empty
          )(zio: => ZIO[R, E1, A1])(implicit trace: Trace): ZIO[R, E1, A1] =
            ZIO.acquireReleaseWith {
              startChild(Context.root().`with`(span), spanName, spanKind, attributes, links)
            } { case (endSpan, _) =>
              endSpan
            } { case (_, ctx) =>
              finalizeSpanUsingEffect(zio, ctx, statusMapper)
            }

          private def setSuccessStatus[E, A](span: JSpan, a: A, statusMapper: StatusMapper[E, A]): UIO[JSpan] =
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
            span: JSpan,
            cause: Cause[E],
            statusMapper: StatusMapper[E, A]
          )(implicit trace: Trace): UIO[JSpan] = {
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
              .tapErrorCause(setFailureStatus(JSpan.fromContext(ctx), _, statusMapper))
              .tap(setSuccessStatus(JSpan.fromContext(ctx), _, statusMapper))

          private def currentNanos(implicit trace: Trace): UIO[Long] =
            Clock.currentTime(TimeUnit.NANOSECONDS)

          private def startRoot(
            spanName: String,
            spanKind: SpanKind,
            attributes: Attributes,
            links: Seq[SpanContext]
          )(implicit trace: Trace): UIO[(UIO[Unit], Context)] =
            for {
              nanos         <- currentNanos
              allAttributes <- withLogAnnotations(attributes)
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

          private def startChild(
            parentCtx: Context,
            spanName: String,
            spanKind: SpanKind,
            attributes: Attributes,
            links: Seq[SpanContext]
          )(implicit trace: Trace): UIO[(UIO[Unit], Context)] =
            for {
              nanos         <- currentNanos
              allAttributes <- withLogAnnotations(attributes)
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

          private def endSpan(span: JSpan)(implicit trace: Trace): UIO[Unit] =
            currentNanos.flatMap(nanos => ZIO.succeed(span.end(nanos, TimeUnit.NANOSECONDS)))

          private def withLogAnnotations(attributes: Attributes): UIO[Attributes] =
            if (logAnnotated) {
              ZIO.logAnnotations.map { annotations =>
                annotations
                  .foldLeft(Attributes.builder()) { case (builder, (annotationKey, annotationValue)) =>
                    builder.put(annotationKey, annotationValue)
                  }
                  .putAll(attributes)
                  .build()
              }
            } else ZIO.succeed(attributes)

        }
      }

    // TODO: consider removing it
    def release(tracer: Tracer) =
      tracer.getCurrentSpanUnsafe.flatMap(span => ZIO.succeed(span.end()))

    ZIO.acquireRelease(acquire)(release)
  }

}
