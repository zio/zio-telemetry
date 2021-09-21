package zio.telemetry.opencensus

import zio._

import io.opencensus.trace.AttributeValue
import io.opencensus.trace.SpanContext
import io.opencensus.trace.Span

object syntax {
  implicit final class OpenCensusZioOps[-R, +E, +A](val effect: ZIO[R, E, A]) extends AnyVal {
    def span(
      name: String,
      kind: Span.Kind = null,
      toErrorStatus: ErrorMapper[E] = Tracing.defaultMapper[E],
      attributes: Map[String, AttributeValue]
    ): ZIO[R with Tracing, E, A] =
      Tracing.span(name, kind, toErrorStatus, attributes)(effect)

    def root(
      name: String,
      kind: Span.Kind = null,
      toErrorStatus: ErrorMapper[E] = Tracing.defaultMapper[E],
      attributes: Map[String, AttributeValue]
    ): ZIO[R with Tracing, E, A] =
      Tracing.root(name, kind, toErrorStatus, attributes)(effect)

    def fromRemoteSpan(
      remote: SpanContext,
      name: String,
      kind: Span.Kind,
      toErrorStatus: ErrorMapper[E],
      attributes: Map[String, AttributeValue]
    ): ZIO[R with Tracing, E, A] =
      Tracing.fromRemoteSpan(remote, name, kind, toErrorStatus, attributes)(effect)

    def withAttributes(
      attributes: (String, AttributeValue)*
    ): ZIO[R with Tracing, E, A] =
      Tracing.withAttributes(attributes: _*)(effect)
  }
}
