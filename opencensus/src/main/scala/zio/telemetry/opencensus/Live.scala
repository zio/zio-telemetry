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
  val live: URLayer[Tracer, Tracing] =
    ZLayer.fromManaged(for {
      tracer  <- ZIO.access[Tracer](_.get).toManaged
      tracing  = FiberRef.make[Span](BlankSpan.INSTANCE).map(new Live(tracer, _))
      managed <- ZManaged.acquireReleaseWith(tracing)(_.end)
    } yield managed)
}

class Live(tracer: Tracer, root: FiberRef[Span]) extends Tracing.Service {
  val currentSpan_ = root

  def currentSpan: UIO[Span] = currentSpan_.get

  def span[R, E, A](
    name: String,
    kind: Span.Kind = Span.Kind.SERVER,
    toErrorStatus: ErrorMapper[E],
    attributes: Map[String, AttributeValue]
  )(effect: ZIO[R, E, A]): ZIO[R, E, A] =
    for {
      parent <- currentSpan_.get
      res    <- createSpan(parent, name, kind).use(
                  finalizeSpanUsingEffect(
                    putAttributes(attributes) *> effect,
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
                 toErrorStatus
               )
             )
    } yield res

  def putAttributes(
    attributes: Map[String, AttributeValue]
  ): ZIO[Any, Nothing, Unit] =
    for {
      current <- currentSpan_.get
      _       <- UIO(attributes.foreach { case ((k, v)) =>
                   current.putAttribute(k, v)
                 })
    } yield ()

  private def createSpan(
    parent: Span,
    name: String,
    kind: Span.Kind
  ): UManaged[Span] =
    ZManaged.acquireReleaseWith(
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
    ZManaged.acquireReleaseWith(
      UIO(
        tracer
          .spanBuilderWithRemoteParent(name, parent)
          .setSpanKind(kind)
          .startSpan()
      )
    )(span => UIO(span.end))

  private def finalizeSpanUsingEffect[R, E, A](
    effect: ZIO[R, E, A],
    toErrorStatus: ErrorMapper[E]
  )(span: Span): ZIO[R, E, A] =
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
