package zio.telemetry.opentelemetry.zio.logging

import zio._
import zio.telemetry.opentelemetry.OpenTelemetry

object ZioLogging {

  def logFormats: URLayer[OpenTelemetry, LogFormats] =
    ZLayer {
      for {
        openTelemetry <- ZIO.service[OpenTelemetry]
      } yield LogFormats.make(openTelemetry.ctxStorage)
    }

}
