package zio.telemetry.opencensus

import zio._
import io.opencensus.trace.Span
import io.opencensus.trace.AttributeValue
import io.opencensus.trace.SpanContext
import io.opencensus.trace.propagation.TextFormat
import zio.telemetry.opencensus.Tracing.defaultMapper

trait Tracing {

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

  def fromRootSpan[C, R, E, A](
    format: TextFormat,
    carrier: C,
    getter: TextFormat.Getter[C],
    name: String,
    kind: Span.Kind = Span.Kind.SERVER,
    toErrorStatus: ErrorMapper[E] = defaultMapper[E],
    attributes: Map[String, AttributeValue] = Map()
  )(effect: ZIO[R, E, A]): ZIO[R, E, A]

  def inject[C](
    format: TextFormat,
    carrier: C,
    setter: TextFormat.Setter[C]
  ): UIO[Unit]

  def putAttributes(
    attrs: Map[String, AttributeValue]
  ): UIO[Unit]

  private[opencensus] def end: UIO[Unit]

}

object Tracing {

  def defaultMapper[E]: ErrorMapper[E] =
    Map.empty

  def span[R, E, A](
    name: String,
    kind: Span.Kind = null,
    toErrorStatus: ErrorMapper[E] = defaultMapper[E],
    attributes: Map[String, AttributeValue] = Map()
  )(effect: ZIO[R, E, A]): ZIO[R with Tracing, E, A] =
    ZIO.serviceWithZIO[Tracing](_.span(name, kind, toErrorStatus, attributes)(effect))

  def root[R, E, A](
    name: String,
    kind: Span.Kind = null,
    toErrorStatus: ErrorMapper[E] = defaultMapper[E],
    attributes: Map[String, AttributeValue] = Map()
  )(effect: ZIO[R, E, A]): ZIO[R with Tracing, E, A] =
    ZIO.serviceWithZIO[Tracing](_.root(name, kind, toErrorStatus, attributes)(effect))

  def fromRemoteSpan[R, E, A](
    remote: SpanContext,
    name: String,
    kind: Span.Kind = null,
    toErrorStatus: ErrorMapper[E] = defaultMapper[E],
    attributes: Map[String, AttributeValue] = Map()
  )(effect: ZIO[R, E, A]): ZIO[R with Tracing, E, A] =
    ZIO.serviceWithZIO[Tracing](_.fromRemoteSpan(remote, name, kind, toErrorStatus, attributes)(effect))

  def fromRootSpan[C, R, E, A](
    format: TextFormat,
    carrier: C,
    getter: TextFormat.Getter[C],
    name: String,
    kind: Span.Kind = Span.Kind.SERVER,
    toErrorStatus: ErrorMapper[E] = defaultMapper[E],
    attributes: Map[String, AttributeValue] = Map()
  )(effect: ZIO[R, E, A]): ZIO[R with Tracing, E, A] =
    ZIO.serviceWithZIO[Tracing](_.fromRootSpan(format, carrier, getter, name, kind, toErrorStatus, attributes)(effect))

  def putAttributes(
    attributes: (String, AttributeValue)*
  ): URIO[Tracing, Unit] =
    ZIO.serviceWithZIO[Tracing](_.putAttributes(attributes.toMap))

  def withAttributes[R, E, A](
    attributes: (String, AttributeValue)*
  )(eff: ZIO[R, E, A]): ZIO[R with Tracing, E, A] =
    ZIO.serviceWithZIO[Tracing](_.putAttributes(attributes.toMap)) *> eff

  def inject[C, R](
    format: TextFormat,
    carrier: C,
    setter: TextFormat.Setter[C]
  ): URIO[R with Tracing, Unit] =
    ZIO.serviceWithZIO[Tracing](_.inject(format, carrier, setter))

}
