package zio.telemetry

import io.opentracing.SpanContext
import io.opentracing.propagation.Format
import zio.Task
import zio.ZIO
import zio.clock.Clock

object opentracing {
  implicit final class OpenTracingOps(val telemetry: Telemetry.Service) extends AnyVal {
    def spanFrom[R, R1 <: R with Clock with Telemetry, E, A, C <: Object](
      format: Format[C],
      carrier: C,
      zio: ZIO[R, E, A],
      opName: String,
      tagError: Boolean = true,
      logError: Boolean = true
    ): ZIO[R1, E, A] =
      Task(telemetry.tracer.extract(format, carrier))
        .fold(_ => None, Option.apply)
        .flatMap {
          case None => zio
          case Some(spanCtx) =>
            telemetry.span(
              zio,
              telemetry.tracer.buildSpan(opName).asChildOf(spanCtx).start,
              tagError,
              logError
            )
        }

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

  implicit final class OpenTracingZIOOps[R, E, A](private val zio: ZIO[R, E, A]) extends AnyVal {
    def spanFrom[R1 <: R with Clock with Telemetry, C <: Object](
      format: Format[C],
      carrier: C,
      opName: String,
      tagError: Boolean = true,
      logError: Boolean = true
    ): ZIO[R1, E, A] =
      ZIO.accessM(_.telemetry.spanFrom(format, carrier, zio, opName, tagError, logError))
  }
}
