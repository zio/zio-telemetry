package zio.telemetry.opentelemetry.instrumentation.example

import zio.http._
import zio._
import zio.config.ReadError
import zio.config.magnolia.descriptor
import zio.config.typesafe.TypesafeConfig
import zio.telemetry.opentelemetry.instrumentation.example.config.AppConfig
import zio.telemetry.opentelemetry.instrumentation.example.http.HttpClient

object ClientApp extends ZIOAppDefault {

  private val configLayer: Layer[ReadError[String], AppConfig] =
    TypesafeConfig.fromResourcePath(descriptor[AppConfig])

  override def run: ZIO[Environment with ZIOAppArgs with Scope, Any, Any] =
    ZIO
      .serviceWithZIO[HttpClient](_.health)
      .provide(
        configLayer,
        Client.default,
        HttpClient.live
      )
}
