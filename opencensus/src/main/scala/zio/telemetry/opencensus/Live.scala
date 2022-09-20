package zio.telemetry.opencensus

import zio._
import io.opencensus.trace.AttributeValue
import io.opencensus.trace.BlankSpan
import io.opencensus.trace.Span
import io.opencensus.trace.SpanContext
import io.opencensus.trace.Status
import io.opencensus.trace.propagation.TextFormat
import io.opencensus.trace.Tracer
import zio.telemetry.opencensus.Tracing.defaultMapper

object Live {
  val live: URLayer[Tracer, Tracing] =
    ZLayer.scoped(for {
      tracer <- ZIO.service[Tracer]
      tracing = FiberRef.make[Span](BlankSpan.INSTANCE).map(new Live(tracer, _))
      live   <- ZIO.acquireRelease(tracing)(_.end)
    } yield live)
}

class Live(tracer: Tracer, root: FiberRef[Span]) extends Tracing {
  val currentSpan_ : FiberRef[Span] = root

  def currentSpan: UIO[Span] = currentSpan_.get

  def span[R, E, A](
    name: String,
    kind: Span.Kind = Span.Kind.SERVER,
    toErrorStatus: ErrorMapper[E],
    attributes: Map[String, AttributeValue]
  )(effect: ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.scoped[R] {
      for {
        parent <- currentSpan_.get
        span   <- createSpan(parent, name, kind)
        res    <- finalizeSpanUsingEffect(putAttributes(attributes) *> effect, toErrorStatus)(span)
      } yield res
    }

  def root[R, E, A](
    name: String,
    kind: Span.Kind,
    toErrorStatus: ErrorMapper[E],
    attributes: Map[String, AttributeValue]
  )(effect: ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.scoped[R] {
      createSpan(BlankSpan.INSTANCE, name, kind).flatMap(span =>
        finalizeSpanUsingEffect(
          putAttributes(attributes) *> effect,
          toErrorStatus
        )(span)
      )
    }

  def fromRemoteSpan[R, E, A](
    remote: SpanContext,
    name: String,
    kind: Span.Kind,
    toErrorStatus: ErrorMapper[E],
    attributes: Map[String, AttributeValue]
  )(effect: ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.scoped[R] {
      createSpanFromRemote(remote, name, kind).flatMap(span =>
        finalizeSpanUsingEffect(
          putAttributes(attributes) *> effect,
          toErrorStatus
        )(span)
      )
    }

  def fromRootSpan[C, R, E, A](
    format: TextFormat,
    carrier: C,
    getter: TextFormat.Getter[C],
    name: String,
    kind: Span.Kind = Span.Kind.SERVER,
    toErrorStatus: ErrorMapper[E] = defaultMapper[E],
    attributes: Map[String, AttributeValue] = Map()
  )(effect: ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO
      .attempt(format.extract(carrier, getter))
      .foldZIO(
        _ => root(name, kind, toErrorStatus)(effect),
        remote => fromRemoteSpan(remote, name, kind, toErrorStatus, attributes)(effect)
      )

  def putAttributes(
    attributes: Map[String, AttributeValue]
  ): UIO[Unit] =
    for {
      current <- currentSpan_.get
      _       <- ZIO.succeed(attributes.foreach { case (k, v) =>
                   current.putAttribute(k, v)
                 })
    } yield ()

  def inject[C](
    format: TextFormat,
    carrier: C,
    setter: TextFormat.Setter[C]
  ): UIO[Unit] =
    for {
      current <- currentSpan
      _       <- ZIO.succeed(format.inject(current.getContext, carrier, setter))
    } yield ()

  private def createSpan(
    parent: Span,
    name: String,
    kind: Span.Kind
  ): URIO[Scope, Span] =
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
  ): URIO[Scope, Span] =
    ZIO.acquireRelease(
      ZIO.succeed(
        tracer
          .spanBuilderWithRemoteParent(name, parent)
          .setSpanKind(kind)
          .startSpan()
      )
    )(span => ZIO.succeed(span.end()))

  private def finalizeSpanUsingEffect[R, E, A](
    effect: ZIO[R, E, A],
    toErrorStatus: ErrorMapper[E]
  )(span: Span): ZIO[R, E, A] =
    for {
      r <- currentSpan_
             .locally(span)(effect)
             .tapErrorCause(setErrorStatus(span, _, toErrorStatus))
    } yield r

  private[opencensus] def end: UIO[Unit] =
    for {
      span <- currentSpan_.get
      _    <- ZIO.succeed(span.end())
    } yield ()

  private def setErrorStatus[E](
    span: Span,
    cause: Cause[E],
    toErrorStatus: ErrorMapper[E]
  ): UIO[Unit] = {
    val errorStatus =
      cause.failureOption.flatMap(toErrorStatus.lift).getOrElse(Status.UNKNOWN)
    ZIO.succeed(span.setStatus(errorStatus))
  }
}
