package zio.telemetry.opencensus

import zio._

import io.opencensus.trace.AttributeValue
import io.opencensus.trace.BlankSpan
import io.opencensus.trace.Span
import io.opencensus.trace.SpanContext
import io.opencensus.trace.Status
import io.opencensus.trace.propagation.TextFormat
import io.opencensus.trace.Tracer

object Live {
  private def managed(tracer: Tracer, root: Span): ZManaged[
    Any,
    Nothing,
    Live
  ] = {
    def end(tracing: Tracing.Service): UIO[Unit] =
      tracing.end()

    val tracing = for {
      rootRef <- FiberRef.make[Span](root)
    } yield new Live(tracer, rootRef)

    ZManaged.make(tracing)(end)
  }

  val live: ZLayer[Has[Tracer], Nothing, Tracing] =
    ZLayer.fromManaged(for {
      tracer  <- ZIO.access[Has[Tracer]](_.get).toManaged_
      service <- managed(tracer, BlankSpan.INSTANCE)
    } yield service)
}

class Live(tracer: Tracer, root: FiberRef[Span]) extends Tracing.Service {
  val currentSpan_ = root

  def currentSpan: ZIO[Any, Nothing, Span] = currentSpan_.get

  def span[R, E, A](
    name: String,
    kind: Span.Kind = Span.Kind.SERVER,
    toErrorStatus: ErrorMapper[E],
    attributes: Map[String, AttributeValue]
  )(effect: ZIO[R, E, A]): ZIO[R, E, A] =
    for {
      parent <- currentSpan_.get
      res <- createSpan(parent, name, kind).use(
              finalizeSpanUsingEffect(
                putAttributes(attributes) *> effect,
                _,
                toErrorStatus
              )
            )
    } yield res

  def root[R, E, A](
    name: String,
    kind: Span.Kind,
    toErrorStatus: ErrorMapper[E],
    attributes: Map[String, AttributeValue]
  )(effect: ZIO[R, E, A]): ZIO[R, E, A] =
    for {
      res <- createSpan(BlankSpan.INSTANCE, name, kind).use(
              finalizeSpanUsingEffect(
                putAttributes(attributes) *> effect,
                _,
                toErrorStatus
              )
            )
    } yield res

  def fromRemoteSpan[R, E, A](
    remote: SpanContext,
    name: String,
    kind: Span.Kind,
    toErrorStatus: ErrorMapper[E],
    attributes: Map[String, AttributeValue]
  )(effect: ZIO[R, E, A]): ZIO[R, E, A] =
    for {
      res <- createSpanFromRemote(remote, name, kind).use(
              finalizeSpanUsingEffect(
                putAttributes(attributes) *> effect,
                _,
                toErrorStatus
              )
            )
    } yield res

  def putAttributes(
    attributes: Map[String, AttributeValue]
  ): ZIO[Any, Nothing, Unit] =
    for {
      current <- currentSpan_.get
      _ <- UIO(attributes.foreach({
            case ((k, v)) => current.putAttribute(k, v)
          }))
    } yield ()

  private def createSpan(
    parent: Span,
    name: String,
    kind: Span.Kind
  ): UManaged[Span] =
    ZManaged.make(
      UIO(
        tracer
          .spanBuilderWithExplicitParent(name, parent)
          .setSpanKind(kind)
          .startSpan()
      )
    )(span => UIO(span.end))

  private def createSpanFromRemote(
    parent: SpanContext,
    name: String,
    kind: Span.Kind
  ): UManaged[Span] =
    ZManaged.make(
      UIO(
        tracer
          .spanBuilderWithRemoteParent(name, parent)
          .setSpanKind(kind)
          .startSpan()
      )
    )(span => UIO(span.end))

  private def finalizeSpanUsingEffect[R, E, A](
    effect: ZIO[R, E, A],
    span: Span,
    toErrorStatus: ErrorMapper[E]
  ): ZIO[R, E, A] =
    for {
      r <- currentSpan_
            .locally(span)(effect)
            .tapCause(setErrorStatus(span, _, toErrorStatus))
    } yield r

  def inject[C, R, E, A](
    format: TextFormat,
    carrier: C,
    setter: TextFormat.Setter[C]
  ): UIO[Unit] =
    for {
      current <- currentSpan
      _       <- URIO(format.inject(current.getContext(), carrier, setter))
    } yield ()

  private[opencensus] def end: UIO[Unit] =
    for {
      span <- currentSpan_.get
      _    <- UIO(span.end())
    } yield ()

  private def setErrorStatus[E](
    span: Span,
    cause: Cause[E],
    toErrorStatus: ErrorMapper[E]
  ): UIO[Unit] = {
    val errorStatus =
      cause.failureOption.flatMap(toErrorStatus.lift).getOrElse(Status.UNKNOWN)
    UIO(span.setStatus(errorStatus))
  }
}
