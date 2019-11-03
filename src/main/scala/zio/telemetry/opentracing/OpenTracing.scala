package zio.telemetry.opentracing

import io.opentracing.Span
import io.opentracing.Tracer
import zio.telemetry.Telemetry

trait OpenTracing extends Telemetry {
  override def telemetry: OpenTracing.Service
}

object OpenTracing {
  trait Service extends Telemetry.Service {
    override type A = Span
    private[opentracing] val tracer: Tracer
  }
}
