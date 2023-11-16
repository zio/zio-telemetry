package zio.telemetry.opentelemetry.instrumentation.example

import io.opentelemetry.api.{GlobalOpenTelemetry, OpenTelemetry}
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import zio.logging.backend.SLF4J
import zio._
import zio.config.ReadError
import zio.config.typesafe.TypesafeConfig
import zio.config.magnolia._
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.instrumentation.example.config.AppConfig
import zio.telemetry.opentelemetry.instrumentation.example.http.{HttpServer, HttpServerApp}
import zio.telemetry.opentelemetry.tracing.Tracing

object ServerApp extends ZIOAppDefault {

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  private val instrumentationScopeName = "zio.telemetry.opentelemetry.instrumentation.example.ServerApp"

  private val configLayer: Layer[ReadError[String], AppConfig] =
    TypesafeConfig.fromResourcePath(descriptor[AppConfig])

  private val globalTracerLayer: TaskLayer[Tracer] =
    ZLayer.fromZIO(
      ZIO.attempt(GlobalOpenTelemetry.getTracer(instrumentationScopeName))
    )

  private val globalOpenTelemetry: TaskLayer[OpenTelemetry] =
    ZLayer(ZIO.attempt(GlobalOpenTelemetry.get()))

  override def run: Task[ExitCode] =
    (for {
      server        <- ZIO.service[HttpServer]
      openTelemetry <- ZIO.service[OpenTelemetry]
      _             <- ZIO.attempt(OpenTelemetryAppender.install(openTelemetry))
      exitCode      <- server.start.exitCode
    } yield exitCode).provide(
      configLayer,
      HttpServer.live,
      HttpServerApp.live,
      Tracing.live,
      ContextStorage.native,
      globalTracerLayer,
      globalOpenTelemetry
    )

}
