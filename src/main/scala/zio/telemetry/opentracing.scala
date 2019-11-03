package zio.telemetry

import io.opentracing.SpanContext
import io.opentracing.propagation.Format
import zio.ZIO
import zio.clock.Clock

object opentracing {
  implicit final class OpenTracingOps(val telemetry: Telemetry.Service) extends AnyVal {
    def inject[R, R1 <: R with Clock with Telemetry, E, A, C <: Object](
      format: Format[C],
      carrier: C
    ): ZIO[Telemetry, Nothing, Unit] =
      telemetry.currentSpan.get.flatMap { span =>
        ZIO.effectTotal(telemetry.tracer.inject(span.context(), format, carrier)).unit
      }

    def context: ZIO[Telemetry, Nothing, SpanContext] =
      telemetry.currentSpan.get.map(_.context)
  }
}
