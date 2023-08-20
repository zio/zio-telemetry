package zio.telemetry.opentelemetry.example

import zio.config.magnolia._
import zio.config.typesafe.TypesafeConfig
import zio.telemetry.opentelemetry.example.config.AppConfig
import zio.telemetry.opentelemetry.example.http.{BackendHttpApp, BackendHttpServer}
import zio._
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.logging.Logging
import zio.telemetry.opentelemetry.tracing.Tracing

object BackendApp extends ZIOAppDefault {

  private val configLayer = TypesafeConfig.fromResourcePath(descriptor[AppConfig])

  private val instrumentationScopeName = "zio.telemetry.opentelemetry.example.BackendApp"
  private val resourceName             = "opentelemetry-example-backend"

  override val bootstrap: ZLayer[ZIOAppArgs, Throwable, Any] =
    Runtime.removeDefaultLoggers >>>
      (FluentbitLoggerProvider.live(resourceName) ++ ContextStorage.fiberRef) >>>
      Logging.live(instrumentationScopeName)

  override def run: ZIO[Scope, Any, ExitCode] =
    ZIO
      .serviceWithZIO[BackendHttpServer] { s =>
        ZIO.logInfo("Starting backend server...") *>
          s.start.exitCode
      }
      .provide(
        configLayer,
        BackendHttpServer.live,
        BackendHttpApp.live,
        Tracing.live,
        Baggage.live(),
        ContextStorage.fiberRef,
        FluentbitTracer.live(resourceName, instrumentationScopeName)
      )

}
