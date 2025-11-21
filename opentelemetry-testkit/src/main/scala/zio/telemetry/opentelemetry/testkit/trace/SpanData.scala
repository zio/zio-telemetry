package zio.telemetry.opentelemetry.testkit.trace

import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.trace.data.{SpanData => JSpanData}
import io.opentelemetry.sdk.trace.data.{StatusData => JStatusData}
import io.opentelemetry.sdk.trace.data.{EventData => JEventData}
import io.opentelemetry.sdk.trace.data.{LinkData => JLinkData}
import zio.telemetry.opentelemetry.testkit.trace.SpanData.StatusData
import zio.telemetry.opentelemetry.testkit.common.Attributes
import zio.telemetry.opentelemetry.testkit.trace.SpanData.EventData
import scala.jdk.CollectionConverters._
import zio.telemetry.opentelemetry.testkit.trace.SpanData.LinkData
import zio.telemetry.opentelemetry.testkit.common.SpanContext

final case class SpanData(
  name: String,
  kind: SpanKind,
  traceId: String,
  spanId: String,
  parentSpanId: String,
  status: StatusData,
  attributes: Attributes,
  events: List[EventData],
  links: List[LinkData]
)

object SpanData {

  def apply(underlying: JSpanData): SpanData =
    SpanData(
      name = underlying.getName,
      kind = underlying.getKind,
      traceId = underlying.getTraceId,
      spanId = underlying.getSpanId,
      parentSpanId = underlying.getParentSpanId,
      status = StatusData(underlying.getStatus),
      attributes = Attributes(underlying.getAttributes),
      events = underlying.getEvents.asScala.toList.map(EventData(_)),
      links = underlying.getLinks.asScala.toList.map(LinkData(_))
    )

  final case class StatusData(
    statusCode: StatusCode,
    description: String
  )

  object StatusData {

    def apply(underlying: JStatusData): StatusData =
      StatusData(
        statusCode = underlying.getStatusCode,
        description = underlying.getDescription
      )

  }

  final case class EventData(
    name: String,
    attributes: Attributes,
    epochNanos: Long
  )

  object EventData {
    def apply(underlying: JEventData): EventData =
      EventData(
        name = underlying.getName,
        attributes = Attributes(underlying.getAttributes),
        epochNanos = underlying.getEpochNanos
      )
  }

  final case class LinkData(
    spanContext: SpanContext,
    attributes: Attributes
  )

  object LinkData {
    def apply(underlying: JLinkData): LinkData =
      LinkData(
        spanContext = SpanContext(underlying.getSpanContext),
        attributes = Attributes(underlying.getAttributes)
      )

  }

}
