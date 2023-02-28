package zio.telemetry.opentelemetry.instrumentation.example

import zio._
import zio.config.typesafe.TypesafeConfig
import zio.config.magnolia._
import zio.telemetry.opentelemetry.instrumentation.example.config.AppConfig
import zio.telemetry.opentelemetry.instrumentation.example.http.HttpServer
import zio.telemetry.opentelemetry.context.ContextStorage

object ServerApp extends ZIOAppDefault {

  private val configLayer = TypesafeConfig.fromResourcePath(descriptor[AppConfig])

  override def run: Task[ExitCode] =
    ZIO
      .serviceWithZIO[HttpServer](_.start.exitCode)
      .provide(
        configLayer,
        HttpServer.live,
        ContextStorage.fiberRef
      )

}
