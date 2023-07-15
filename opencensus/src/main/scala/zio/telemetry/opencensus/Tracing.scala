package zio.telemetry.opencensus

import io.opencensus.trace._
import io.opencensus.trace.propagation.TextFormat
import zio._

trait Tracing { self =>

  def getCurrentSpan: UIO[Span]

  def span[R, E, E1 <: E, A](
    name: String,
    kind: Span.Kind = Span.Kind.SERVER,
    toErrorStatus: ErrorMapper[E] = ErrorMapper.default,
    attributes: Map[String, AttributeValue] = Map.empty
  )(zio: => ZIO[R, E1, A])(implicit trace: Trace): ZIO[R, E1, A]

  def root[R, E, E1 <: E, A](
    name: String,
    kind: Span.Kind = Span.Kind.SERVER,
    toErrorStatus: ErrorMapper[E] = ErrorMapper.default,
    attributes: Map[String, AttributeValue] = Map.empty
  )(zio: => ZIO[R, E1, A])(implicit trace: Trace): ZIO[R, E1, A]

  def fromRemoteSpan[R, E, E1 <: E, A](
    remote: SpanContext,
    name: String,
    kind: Span.Kind = Span.Kind.SERVER,
    toErrorStatus: ErrorMapper[E] = ErrorMapper.default,
    attributes: Map[String, AttributeValue] = Map.empty
  )(zio: => ZIO[R, E1, A])(implicit trace: Trace): ZIO[R, E1, A]

  def fromRootSpan[C, R, E, E1 <: E, A](
    format: TextFormat,
    carrier: C,
    getter: TextFormat.Getter[C],
    name: String,
    kind: Span.Kind = Span.Kind.SERVER,
    toErrorStatus: ErrorMapper[E] = ErrorMapper.default,
    attributes: Map[String, AttributeValue] = Map.empty
  )(zio: => ZIO[R, E1, A])(implicit trace: Trace): ZIO[R, E1, A]

  def inject[C](
    format: TextFormat,
    carrier: C,
    setter: TextFormat.Setter[C]
  )(implicit trace: Trace): UIO[Unit]

  def putAttributes(
    attrs: Map[String, AttributeValue]
  )(implicit trace: Trace): UIO[Unit]

  object aspects {

    def span[E](
      name: String,
      kind: Span.Kind = Span.Kind.SERVER,
      toErrorStatus: ErrorMapper[E] = ErrorMapper.default,
      attributes: Map[String, AttributeValue] = Map.empty
    ): ZIOAspect[Nothing, Any, Nothing, E, Nothing, Any] =
      new ZIOAspect[Nothing, Any, Nothing, E, Nothing, Any] {
        override def apply[R, E1 <: E, A](zio: ZIO[R, E1, A])(implicit trace: Trace): ZIO[R, E1, A] =
          self.span(name, kind, toErrorStatus, attributes)(zio)
      }

    def root[E](
      name: String,
      kind: Span.Kind = Span.Kind.SERVER,
      toErrorStatus: ErrorMapper[E] = ErrorMapper.default,
      attributes: Map[String, AttributeValue] = Map.empty
    ): ZIOAspect[Nothing, Any, Nothing, E, Nothing, Any] =
      new ZIOAspect[Nothing, Any, Nothing, E, Nothing, Any] {
        override def apply[R, E1 <: E, A](zio: ZIO[R, E1, A])(implicit trace: Trace): ZIO[R, E1, A] =
          self.root(name, kind, toErrorStatus, attributes)(zio)
      }

    def fromRemoteSpan[E](
      remote: SpanContext,
      name: String,
      kind: Span.Kind = Span.Kind.SERVER,
      toErrorStatus: ErrorMapper[E] = ErrorMapper.default,
      attributes: Map[String, AttributeValue] = Map.empty
    ): ZIOAspect[Nothing, Any, Nothing, E, Nothing, Any] =
      new ZIOAspect[Nothing, Any, Nothing, E, Nothing, Any] {
        override def apply[R, E1 <: E, A](zio: ZIO[R, E1, A])(implicit trace: Trace): ZIO[R, E1, A] =
          self.fromRemoteSpan(remote, name, kind, toErrorStatus, attributes)(zio)
      }

    def fromRootSpan[C, E](
      format: TextFormat,
      carrier: C,
      getter: TextFormat.Getter[C],
      name: String,
      kind: Span.Kind = Span.Kind.SERVER,
      toErrorStatus: ErrorMapper[E] = ErrorMapper.default,
      attributes: Map[String, AttributeValue] = Map.empty
    ): ZIOAspect[Nothing, Any, Nothing, E, Nothing, Any] =
      new ZIOAspect[Nothing, Any, Nothing, E, Nothing, Any] {
        override def apply[R, E1 <: E, A](zio: ZIO[R, E1, A])(implicit trace: Trace): ZIO[R, E1, A] =
          self.fromRootSpan(format, carrier, getter, name, kind, toErrorStatus, attributes)(zio)
      }

    def inject[C](
      format: TextFormat,
      carrier: C,
      setter: TextFormat.Setter[C]
    ): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
      new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
        override def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          self.inject(format, carrier, setter) *> zio
      }

    def withAttributes(
      attrs: (String, AttributeValue)*
    ): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
      new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
        override def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          self.putAttributes(attrs.toMap) *> zio
      }

  }

}

object Tracing {

  val live: URLayer[Tracer, Tracing] =
    ZLayer.scoped(ZIO.service[Tracer].flatMap(scoped))

  def scoped(tracer: Tracer): URIO[Scope, Tracing] = {
    val acquire =
      for {
        currentSpan <- FiberRef.make[Span](BlankSpan.INSTANCE)
      } yield new Tracing { self =>
        override def getCurrentSpan: UIO[Span] =
          currentSpan.get

        override def span[R, E, E1 <: E, A](
          name: String,
          kind: Span.Kind = Span.Kind.SERVER,
          toErrorStatus: ErrorMapper[E] = ErrorMapper.default,
          attributes: Map[String, AttributeValue] = Map.empty
        )(zio: => ZIO[R, E1, A])(implicit trace: Trace): ZIO[R, E1, A] =
          ZIO.scoped[R] {
            for {
              parent <- getCurrentSpan
              span   <- createSpan(parent, name, kind)
              res    <- finalizeSpanUsingEffect(span, toErrorStatus)(putAttributes(attributes) *> zio)
            } yield res
          }

        override def root[R, E, E1 <: E, A](
          name: String,
          kind: Span.Kind = Span.Kind.SERVER,
          toErrorStatus: ErrorMapper[E] = ErrorMapper.default,
          attributes: Map[String, AttributeValue] = Map.empty
        )(zio: => ZIO[R, E1, A])(implicit trace: Trace): ZIO[R, E1, A] =
          ZIO.scoped[R] {
            createSpan(BlankSpan.INSTANCE, name, kind).flatMap { span =>
              finalizeSpanUsingEffect(span, toErrorStatus)(
                putAttributes(attributes) *> zio
              )
            }
          }

        override def fromRemoteSpan[R, E, E1 <: E, A](
          remote: SpanContext,
          name: String,
          kind: Span.Kind = Span.Kind.SERVER,
          toErrorStatus: ErrorMapper[E] = ErrorMapper.default,
          attributes: Map[String, AttributeValue] = Map.empty
        )(zio: => ZIO[R, E1, A])(implicit trace: Trace): ZIO[R, E1, A] =
          ZIO.scoped[R] {
            createSpanFromRemote(remote, name, kind).flatMap { span =>
              finalizeSpanUsingEffect(span, toErrorStatus)(
                putAttributes(attributes) *> zio
              )
            }
          }

        override def fromRootSpan[C, R, E, E1 <: E, A](
          format: TextFormat,
          carrier: C,
          getter: TextFormat.Getter[C],
          name: String,
          kind: Span.Kind = Span.Kind.SERVER,
          toErrorStatus: ErrorMapper[E] = ErrorMapper.default,
          attributes: Map[String, AttributeValue] = Map.empty
        )(zio: => ZIO[R, E1, A])(implicit trace: Trace): ZIO[R, E1, A] =
          ZIO
            .attempt(format.extract(carrier, getter))
            .foldZIO(
              _ => root(name, kind, toErrorStatus)(zio),
              remote => fromRemoteSpan(remote, name, kind, toErrorStatus, attributes)(zio)
            )

        override def putAttributes(
          attributes: Map[String, AttributeValue]
        )(implicit trace: Trace): UIO[Unit] =
          for {
            current <- getCurrentSpan
            _       <- ZIO.succeed(attributes.foreach { case (k, v) =>
                         current.putAttribute(k, v)
                       })
          } yield ()

        override def inject[C](
          format: TextFormat,
          carrier: C,
          setter: TextFormat.Setter[C]
        )(implicit trace: Trace): UIO[Unit] =
          for {
            current <- getCurrentSpan
            _       <- ZIO.succeed(format.inject(current.getContext, carrier, setter))
          } yield ()

        private def createSpan(
          parent: Span,
          name: String,
          kind: Span.Kind
        )(implicit trace: Trace): URIO[Scope, Span] =
          ZIO.acquireRelease(
            ZIO.succeed(
              tracer
                .spanBuilderWithExplicitParent(name, parent)
                .setSpanKind(kind)
                .startSpan()
            )
          )(span => ZIO.succeed(span.end()))

        private def createSpanFromRemote(
          parent: SpanContext,
          name: String,
          kind: Span.Kind
        )(implicit trace: Trace): URIO[Scope, Span] =
          ZIO.acquireRelease(
            ZIO.succeed(
              tracer
                .spanBuilderWithRemoteParent(name, parent)
                .setSpanKind(kind)
                .startSpan()
            )
          )(span => ZIO.succeed(span.end()))

        private def finalizeSpanUsingEffect[R, E, E1 <: E, A](
          span: Span,
          toErrorStatus: ErrorMapper[E]
        )(effect: ZIO[R, E1, A])(implicit trace: Trace): ZIO[R, E1, A] =
          currentSpan
            .locally(span)(effect)
            .tapErrorCause(setErrorStatus(span, _, toErrorStatus))

        private def setErrorStatus[E](
          span: Span,
          cause: Cause[E],
          toErrorStatus: ErrorMapper[E]
        )(implicit trace: Trace): UIO[Unit] = {
          val errorStatus =
            cause.failureOption
              .flatMap(toErrorStatus.body.lift)
              .getOrElse(Status.UNKNOWN)

          ZIO.succeed(span.setStatus(errorStatus))
        }

      }

    def release(tracing: Tracing) =
      tracing.getCurrentSpan.flatMap(span => ZIO.succeed(span.end()))

    ZIO.acquireRelease(acquire)(release)
  }

}
