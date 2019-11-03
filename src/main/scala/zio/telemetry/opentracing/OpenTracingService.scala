package zio.telemetry.opentracing

import io.opentracing.Span
import io.opentracing.Tracer
import zio.telemetry.Telemetry

trait OpenTracingService extends Telemetry.Service[Span] {
  private[opentracing] val tracer: Tracer
}
