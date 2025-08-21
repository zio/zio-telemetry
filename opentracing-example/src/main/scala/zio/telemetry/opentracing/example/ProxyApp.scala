package zio.telemetry.opentracing.example

import zio._
import zio.config.magnolia._
import zio.config.typesafe.TypesafeConfig
import zio.http._
import zio.telemetry.opentracing.OpenTracing
import zio.telemetry.opentracing.example.config.AppConfig
import zio.telemetry.opentracing.example.http.{BackendClient, ProxyHttpApp, ProxyHttpServer}

object ProxyApp extends ZIOAppDefault {

  private val configLayer = TypesafeConfig.fromResourcePath(descriptor[AppConfig])

  override def run: ZIO[Environment with ZIOAppArgs with Scope, Any, Any] =
    ZIO
      .serviceWithZIO[ProxyHttpServer](_.start)
      .provide(
        configLayer,
        Client.default,
        BackendClient.live,
        ProxyHttpServer.live,
        ProxyHttpApp.live,
        OpenTracing.live(),
        JaegerTracer.live("zio-proxy")
      )

}
