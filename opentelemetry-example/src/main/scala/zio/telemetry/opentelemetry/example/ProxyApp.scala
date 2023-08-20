package zio.telemetry.opentelemetry.example

import zio._
import zio.config.magnolia._
import zio.config.typesafe.TypesafeConfig
import zio.http.Client
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.example.config.AppConfig
import zio.telemetry.opentelemetry.example.http.{BackendClient, ProxyHttpApp, ProxyHttpServer}
import zio.telemetry.opentelemetry.logging.Logging
import zio.telemetry.opentelemetry.tracing.Tracing

object ProxyApp extends ZIOAppDefault {

  private val configLayer = TypesafeConfig.fromResourcePath(descriptor[AppConfig])

  private val instrumentationScopeName = "zio.telemetry.opentelemetry.example.ProxyApp"
  private val resourceName             = "opentelemetry-example-proxy"

  override val bootstrap: ZLayer[ZIOAppArgs, Throwable, Any] =
    Runtime.removeDefaultLoggers >>>
      (FluentbitLoggerProvider.live(resourceName) ++ ContextStorage.fiberRef) >>>
      Logging.live(instrumentationScopeName)

  override def run: Task[ExitCode] =
    ZIO
      .serviceWithZIO[ProxyHttpServer](_.start.exitCode)
      .provide(
        configLayer,
        Client.default,
        BackendClient.live,
        ProxyHttpServer.live,
        ProxyHttpApp.live,
        Tracing.live,
        Baggage.live(),
        ContextStorage.fiberRef,
        FluentbitTracer.live(resourceName, instrumentationScopeName)
      )

}
