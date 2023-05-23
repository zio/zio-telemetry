package zio.telemetry.opentelemetry.instrumentation.example

import zio._
import zio.config.ReadError
import zio.config.magnolia.descriptor
import zio.config.typesafe.TypesafeConfig
import zio.telemetry.opentelemetry.instrumentation.example.config.AppConfig
import zio.telemetry.opentelemetry.instrumentation.example.http.HttpClient

object ClientApp extends ZIOAppDefault {

  private val configLayer: Layer[ReadError[String], AppConfig] =
    TypesafeConfig.fromResourcePath(descriptor[AppConfig])

  override def run: Task[ExitCode] =
    ZIO
      .serviceWithZIO[HttpClient](_.health.exitCode)
      .provide(
        configLayer,
        zio.http.Client.default,
        HttpClient.live
      )
}
