package zio.telemetry.opentelemetry.testkit.common

import io.opentelemetry.api.trace.{SpanContext => JSpanContext}

final case class SpanContext(
  spanId: String,
  traceId: String
)

object SpanContext {

  def apply(underlying: JSpanContext): SpanContext =
    SpanContext(
      spanId = underlying.getSpanId,
      traceId = underlying.getTraceId
    )

}
