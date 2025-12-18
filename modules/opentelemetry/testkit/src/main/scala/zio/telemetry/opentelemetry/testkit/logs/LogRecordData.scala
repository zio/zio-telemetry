package zio.telemetry.opentelemetry.testkit.logs

import io.opentelemetry.api.logs.Severity
import io.opentelemetry.sdk.logs.data.{LogRecordData => JLogRecordData}
import zio.telemetry.opentelemetry.testkit.common.{Attributes, InstrumentationScopeInfo, SpanContext}

final case class LogRecordData(
  instrumentationScopeInfo: InstrumentationScopeInfo,
  timestampEpochNanos: Long,
  observedTimestampEpochNanos: Long,
  spanContext: SpanContext,
  severity: Severity,
  severityText: String,
  body: String,
  attributes: Attributes,
  totalAttributeCount: Int
)

object LogRecordData {

  def apply(underlying: JLogRecordData): LogRecordData =
    LogRecordData(
      instrumentationScopeInfo = InstrumentationScopeInfo(underlying.getInstrumentationScopeInfo),
      timestampEpochNanos = underlying.getTimestampEpochNanos,
      observedTimestampEpochNanos = underlying.getObservedTimestampEpochNanos,
      spanContext = SpanContext(underlying.getSpanContext),
      severity = underlying.getSeverity,
      severityText = underlying.getSeverityText,
      body = underlying.getBodyValue().asString,
      attributes = Attributes(underlying.getAttributes),
      totalAttributeCount = underlying.getTotalAttributeCount
    )

}
