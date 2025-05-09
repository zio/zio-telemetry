package zio.telemetry.opentelemetry.trace

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.{SpanBuilder, SpanContext, SpanKind, StatusCode, Tracer => JTracer}
import io.opentelemetry.context.Context
import zio._
import zio.telemetry.opentelemetry.context.internal.ContextStorage

import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext

trait Tracer { self =>

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
  def continueSpan[R, E, E1 <: E, A, A1 <: A](
    span: Span,
    spanName: String,
    spanKind: SpanKind = SpanKind.INTERNAL,
    attributes: Attributes = Attributes.empty(),
    statusMapper: StatusMapper[E, A] = StatusMapper.default,
    links: Seq[SpanContext] = Seq.empty
  )(f: Span => ZIO[R, E1, A1])(implicit trace: Trace): ZIO[R, E1, A1]

  /**
   * Sets the new span to be the new root span with name 'spanName'.
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
  )(f: Span => ZIO[R, E1, A1])(implicit trace: Trace): ZIO[R, E1, A1]

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
  def unmanagedScope[A](effect: => A)(implicit trace: Trace): Task[A]

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
  def unmanagedScopeFuture[A](make: ExecutionContext => scala.concurrent.Future[A])(implicit trace: Trace): Task[A]

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
  def unmanagedScopeTotal[A](effect: => A)(implicit trace: Trace): UIO[A]

  /**
   * Sets the new span to be the child of the current span with name 'spanName'.
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
  )(f: Span => ZIO[R, E1, A1])(implicit trace: Trace): ZIO[R, E1, A1]

  /**
   * Sets the new span to be the child of the current span with name 'spanName'.
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
  )(implicit trace: Trace): ZIO[Scope, Nothing, Span]

  /**
   * Unsafely sets the new span to be the child of the current span with name 'spanName'.
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
  def spanUnmanaged(
    spanName: String,
    spanKind: SpanKind = SpanKind.INTERNAL,
    attributes: Attributes = Attributes.empty(),
    statusMapper: StatusMapper[Any, Unit] = StatusMapper.default,
    links: Seq[SpanContext] = Seq.empty
  )(implicit trace: Trace): ZIO[Any, Nothing, Span]

  object aspects {

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
          self.continueSpan(span, spanName, spanKind, attributes, statusMapper, links)(_ => zio)
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
          self.root(spanName, spanKind, attributes, statusMapper, links)(_ => zio)
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
          self.span(spanName, spanKind, attributes, statusMapper, links)(_ => zio)
      }

  }

}

private[opentelemetry] object Tracer {

  def make(tracer: JTracer, ctxStorage: ContextStorage, logAnnotated: Boolean = false): Tracer =
    new Tracer { self =>
      override def root[R, E, E1 <: E, A, A1 <: A](
        spanName: String,
        spanKind: SpanKind = SpanKind.INTERNAL,
        attributes: Attributes = Attributes.empty(),
        statusMapper: StatusMapper[E, A] = StatusMapper.default,
        links: Seq[SpanContext] = Seq.empty
      )(f: Span => ZIO[R, E1, A1])(implicit trace: Trace): ZIO[R, E1, A1] =
        ZIO.acquireReleaseWith {
          startRoot(spanName, spanKind, attributes, links)
        } { case (span, _) =>
          endSpan(span)
        } { case (span, ctx) =>
          finalizeSpanUsingEffect(f(span), ctx, statusMapper)
        }

      override def span[R, E, E1 <: E, A, A1 <: A](
        spanName: String,
        spanKind: SpanKind = SpanKind.INTERNAL,
        attributes: Attributes = Attributes.empty(),
        statusMapper: StatusMapper[E, A] = StatusMapper.default,
        links: Seq[SpanContext] = Seq.empty
      )(f: Span => ZIO[R, E1, A1])(implicit trace: Trace): ZIO[R, E1, A1] =
        for {
          parentCtx <- ctxStorage.get
          result    <- ZIO.acquireReleaseWith {
                         startChild(parentCtx, spanName, spanKind, attributes, links)
                       } { case (span, _) =>
                         endSpan(span)
                       } { case (span, ctx) =>
                         finalizeSpanUsingEffect(f(span), ctx, statusMapper)
                       }
        } yield result

      override def spanScoped(
        spanName: String,
        spanKind: SpanKind,
        attributes: Attributes,
        statusMapper: StatusMapper[Any, Unit] = StatusMapper.default,
        links: Seq[SpanContext]
      )(implicit trace: Trace): ZIO[Scope, Nothing, Span] =
        for {
          parentCtx  <- ctxStorage.get
          childSpan  <- startChild(parentCtx, spanName, spanKind, attributes, links)
          (span, ctx) = childSpan
          _          <- ctxStorage.locallyScoped(ctx)
          scope      <- ZIO.scope
          _          <- scope.addFinalizerExit { exit =>
                          val setStatus = exit match {
                            case Exit.Success(_)     => ZIO.unit
                            case Exit.Failure(cause) => setFailureStatus(Span.fromContext(ctx), cause, statusMapper)
                          }

                          setStatus *> endSpan(span)
                        }
        } yield span

      override def spanUnmanaged(
        spanName: String,
        spanKind: SpanKind = SpanKind.INTERNAL,
        attributes: Attributes = Attributes.empty(),
        statusMapper: StatusMapper[Any, Unit] = StatusMapper.default,
        links: Seq[SpanContext] = Seq.empty
      )(implicit trace: Trace): ZIO[Any, Nothing, Span] =
        for {
          parentCtx  <- ctxStorage.get
          childSpan  <- startChild(parentCtx, spanName, spanKind, attributes, links)
          (span, ctx) = childSpan
          _          <- ZIO.scoped[Any](
                          for {
                            _     <- ctxStorage.locallyScoped(ctx)
                            scope <- ZIO.scope
                            _     <- scope.addFinalizerExit { exit =>
                                       val setStatus = exit match {
                                         case Exit.Success(_)     => ZIO.unit
                                         case Exit.Failure(cause) => setFailureStatus(Span.fromContext(ctx), cause, statusMapper)
                                       }

                                       setStatus
                                     }
                          } yield ()
                        )
        } yield span

      override def unmanagedScope[A](effect: => A)(implicit trace: Trace): Task[A] =
        for {
          ctx    <- ctxStorage.get
          effect <- ZIO.attempt {
                      val scope = ctx.makeCurrent()
                      try effect
                      finally scope.close()
                    }
        } yield effect

      override def unmanagedScopeTotal[A](effect: => A)(implicit trace: Trace): UIO[A] =
        for {
          ctx    <- ctxStorage.get
          effect <- ZIO.succeed {
                      val scope = ctx.makeCurrent()
                      try effect
                      finally scope.close()
                    }
        } yield effect

      override def unmanagedScopeFuture[A](
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

      override def continueSpan[R, E, E1 <: E, A, A1 <: A](
        span: Span,
        spanName: String,
        spanKind: SpanKind = SpanKind.INTERNAL,
        attributes: Attributes = Attributes.empty(),
        statusMapper: StatusMapper[E, A] = StatusMapper.default,
        links: Seq[SpanContext] = Seq.empty
      )(f: Span => ZIO[R, E1, A1])(implicit trace: Trace): ZIO[R, E1, A1] =
        ZIO.acquireReleaseWith {
          startChild(Context.root().`with`(span.unsafe.asJava), spanName, spanKind, attributes, links)
        } { case (childSpan, _) =>
          endSpan(childSpan)
        } { case (childSpan, ctx) =>
          finalizeSpanUsingEffect(f(childSpan), ctx, statusMapper)
        }

      // TODO: move to StatusMapper
      private def setSuccessStatus[E, A](span: Span, a: A, statusMapper: StatusMapper[E, A]): UIO[Unit] =
        statusMapper.success
          .lift(a)
          .fold(ZIO.unit) { case StatusMapper.Result(statusCode, maybeError) =>
            if (statusCode == StatusCode.ERROR)
              maybeError.fold(span.setStatus(statusCode)) { errorMessage =>
                span.setStatus(statusCode, errorMessage)
              }
            else
              span.setStatus(statusCode)
          }

      // TODO: move to StatusMapper
      private def setFailureStatus[E, A](
        span: Span,
        cause: Cause[E],
        statusMapper: StatusMapper[E, A]
      )(implicit trace: Trace): UIO[Unit] = {
        val result =
          cause.failureOption
            .flatMap(statusMapper.failure.lift)
            .getOrElse(StatusMapper.Result(StatusCode.ERROR, None))

        for {
          _ <- if (result.statusCode == StatusCode.ERROR)
                 span.setStatus(result.statusCode, cause.prettyPrint)
               else
                 span.setStatus(result.statusCode)
          _ <- result.error.fold(ZIO.unit)(span.recordException)
        } yield ()
      }

      /*
        TODO: reimplement
        ctxStorage
          .locally(ctx)(zio)
          .exit
          .map {
            case Exit.Success(value) => setSuccessStatus(Span.fromContext(ctx), value, statusMapper)
            case Exit.Failure(cause) => setFailureStatus(Span.fromContext(ctx), cause, statusMapper)
          }
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

      private def startRoot(
        spanName: String,
        spanKind: SpanKind,
        attributes: Attributes,
        links: Seq[SpanContext]
      )(implicit trace: Trace): UIO[(Span, Context)] =
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
        } yield (Span.make(span), Context.root().`with`(span))

      private def startChild(
        parentCtx: Context,
        spanName: String,
        spanKind: SpanKind,
        attributes: Attributes,
        links: Seq[SpanContext]
      )(implicit trace: Trace): UIO[(Span, Context)] =
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
        } yield (Span.make(span), parentCtx.`with`(span))

      private implicit class SpanBuilderOps(spanBuilder: SpanBuilder) {
        def addLinks(links: Seq[SpanContext]): SpanBuilder =
          links.foldLeft(spanBuilder) { case (builder, link) => builder.addLink(link) }
      }

      private def endSpan(span: Span)(implicit trace: Trace): UIO[Unit] =
        for {
          timestamp <- currentNanos
          _         <- span.end(timestamp, TimeUnit.NANOSECONDS)
        } yield ()

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
