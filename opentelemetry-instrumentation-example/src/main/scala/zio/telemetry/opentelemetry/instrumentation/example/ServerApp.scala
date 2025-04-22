package zio.telemetry.opentelemetry.instrumentation.example

import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import zio._
import zio.config.ReadError
import zio.config.magnolia._
import zio.config.typesafe.TypesafeConfig
import zio.logging.backend.SLF4J
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.instrumentation.example.config.AppConfig
import zio.telemetry.opentelemetry.instrumentation.example.http.{HttpServer, HttpServerApp}

object ServerApp extends ZIOAppDefault {

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  private val instrumentationScopeName = "zio.telemetry.opentelemetry.instrumentation.example.ServerApp"

  private val configLayer: Layer[ReadError[String], AppConfig] =
    TypesafeConfig.fromResourcePath(descriptor[AppConfig])

  override def run: Task[ExitCode] =
    (for {
      server        <- ZIO.service[HttpServer]
      openTelemetry <- ZIO.service[OpenTelemetry]
      _             <- ZIO.attempt(OpenTelemetryAppender.install(openTelemetry.asJava))
      exitCode      <- server.start.exitCode
    } yield exitCode).provide(
      configLayer,
      HttpServer.live,
      HttpServerApp.live,
      OpenTelemetry.global,
      OpenTelemetry.tracing(instrumentationScopeName)
    )

}
