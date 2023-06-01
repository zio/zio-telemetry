package zio.telemetry.opentelemetry.example

import zio._
import zio.config.magnolia._
import zio.config.typesafe.TypesafeConfig
import zio.http.ZClient
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.example.config.AppConfig
import zio.telemetry.opentelemetry.example.http.{Client, ProxyHttpApp, ProxyHttpServer}
import zio.telemetry.opentelemetry.tracing.Tracing

object ProxyApp extends ZIOAppDefault {

  private val configLayer = TypesafeConfig.fromResourcePath(descriptor[AppConfig])

  override def run: Task[ExitCode] =
    ZIO
      .serviceWithZIO[ProxyHttpServer](_.start.exitCode)
      .provide(
        configLayer,
        ZClient.default,
        Client.live,
        ProxyHttpServer.live,
        ProxyHttpApp.live,
        Tracing.live,
        Baggage.live(),
        ContextStorage.fiberRef,
        JaegerTracer.live
      )

}
