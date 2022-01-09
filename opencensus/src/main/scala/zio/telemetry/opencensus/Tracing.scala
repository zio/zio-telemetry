package zio.telemetry.opencensus

import zio._

import io.opencensus.trace.Span
import io.opencensus.trace.AttributeValue
import io.opencensus.trace.SpanContext
import io.opencensus.trace.propagation.TextFormat

object Tracing {
  def defaultMapper[E]: ErrorMapper[E] = Map.empty

  trait Service {
    def span[R, E, A](
      name: String,
      kind: Span.Kind = null,
      toErrorStatus: ErrorMapper[E] = defaultMapper[E],
      attributes: Map[String, AttributeValue] = Map()
    )(effect: ZIO[R, E, A]): ZIO[R, E, A]

    def root[R, E, A](
      name: String,
      kind: Span.Kind = null,
      toErrorStatus: ErrorMapper[E] = defaultMapper[E],
      attributes: Map[String, AttributeValue] = Map()
    )(effect: ZIO[R, E, A]): ZIO[R, E, A]

    def fromRemoteSpan[R, E, A](
      remote: SpanContext,
      name: String,
      kind: Span.Kind = Span.Kind.SERVER,
      toErrorStatus: ErrorMapper[E] = defaultMapper[E],
      attributes: Map[String, AttributeValue] = Map()
    )(effect: ZIO[R, E, A]): ZIO[R, E, A]

    def inject[C, R, E, A](
      format: TextFormat,
      carrier: C,
      setter: TextFormat.Setter[C]
    ): UIO[Unit]

    def putAttributes(
      attrs: Map[String, AttributeValue]
    ): ZIO[Any, Nothing, Unit]

    private[opencensus] def end: UIO[Unit]
  }

  def span[R, E, A](
    name: String,
    kind: Span.Kind = null,
    toErrorStatus: ErrorMapper[E] = defaultMapper[E],
    attributes: Map[String, AttributeValue] = Map()
  )(effect: ZIO[R, E, A]): ZIO[R with Tracing, E, A] =
    ZIO.environmentWithZIO(_.get[Tracing].span(name, kind, toErrorStatus, attributes)(effect))

  def root[R, E, A](
    name: String,
    kind: Span.Kind = null,
    toErrorStatus: ErrorMapper[E] = defaultMapper[E],
    attributes: Map[String, AttributeValue] = Map()
  )(effect: ZIO[R, E, A]): ZIO[R with Tracing, E, A] =
    ZIO.environmentWithZIO(_.get[Tracing].root(name, kind, toErrorStatus, attributes)(effect))

  def fromRemoteSpan[R, E, A](
    remote: SpanContext,
    name: String,
    kind: Span.Kind = null,
    toErrorStatus: ErrorMapper[E] = defaultMapper[E],
    attributes: Map[String, AttributeValue] = Map()
  )(effect: ZIO[R, E, A]): ZIO[R with Tracing, E, A] =
    ZIO.environmentWithZIO(
      _.get[Tracing].fromRemoteSpan(remote, name, kind, toErrorStatus, attributes)(
        effect
      )
    )

  def putAttributes(
    attributes: (String, AttributeValue)*
  ): ZIO[Tracing, Nothing, Unit] =
    ZIO.environmentWithZIO(_.get.putAttributes(attributes.toMap))

  def withAttributes[R, E, A](
    attributes: (String, AttributeValue)*
  )(eff: ZIO[R, E, A]): ZIO[R with Tracing, E, A] =
    ZIO.environmentWithZIO[Tracing](_.get.putAttributes(attributes.toMap)) *> eff

  def fromRootSpan[C, R, E, A](
    format: TextFormat,
    carrier: C,
    getter: TextFormat.Getter[C],
    name: String,
    kind: Span.Kind = Span.Kind.SERVER,
    toErrorStatus: ErrorMapper[E] = defaultMapper[E],
    attributes: Map[String, AttributeValue] = Map()
  )(effect: ZIO[R, E, A]): ZIO[R with Tracing, E, A] =
    Task(format.extract(carrier, getter)).foldZIO(
      _ => root(name, kind, toErrorStatus)(effect),
      remote => fromRemoteSpan(remote, name, kind, toErrorStatus, attributes)(effect)
    )

  def inject[C, R, E, A](
    format: TextFormat,
    carrier: C,
    setter: TextFormat.Setter[C]
  ): URIO[R with Tracing, Unit] =
    ZIO.environmentWithZIO(_.get[Tracing].inject(format, carrier, setter))
}
