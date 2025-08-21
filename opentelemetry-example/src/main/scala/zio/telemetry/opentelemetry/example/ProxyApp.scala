package zio.telemetry.opentelemetry.example

import zio._
import zio.config.magnolia._
import zio.config.typesafe.TypesafeConfig
import zio.http.Client
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.example.config.AppConfig
import zio.telemetry.opentelemetry.example.http.{BackendClient, ProxyHttpApp, ProxyHttpServer}
import zio.telemetry.opentelemetry.example.otel.OtelSdk

object ProxyApp extends ZIOAppDefault {

  private val configLayer = TypesafeConfig.fromResourcePath(descriptor[AppConfig])

  private val instrumentationScopeName = "zio.telemetry.opentelemetry.example.ProxyApp"
  private val resourceName             = "opentelemetry-example-proxy"

  override def run: ZIO[Environment with ZIOAppArgs with Scope, Any, Any] =
    ZIO
      .serviceWithZIO[ProxyHttpServer](_.start)
      .provide(
        configLayer,
        Client.default,
        BackendClient.live,
        ProxyHttpServer.live,
        ProxyHttpApp.live,
        OtelSdk.custom(resourceName),
        OpenTelemetry.tracer(instrumentationScopeName),
        OpenTelemetry.logger(instrumentationScopeName)
      )

}
