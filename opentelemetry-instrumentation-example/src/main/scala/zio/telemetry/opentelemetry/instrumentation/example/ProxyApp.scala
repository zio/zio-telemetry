package zio.telemetry.opentelemetry.instrumentation.example

import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import zio._
import zio.config.magnolia.descriptor
import zio.config.typesafe.TypesafeConfig
import zio.http._
import zio.logging.backend.SLF4J
import zio.telemetry.opentelemetry.Otel
import zio.telemetry.opentelemetry.instrumentation.example.config.AppConfig
import zio.telemetry.opentelemetry.instrumentation.example.http.{BackendClient, ProxyHttpApp, ProxyHttpServer}
import zio.telemetry.opentelemetry.OpenTelemetry

object ProxyApp extends ZIOAppDefault {

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  private val configLayer =
    TypesafeConfig.fromResourcePath(descriptor[AppConfig])

  private val instrumentationScopeName = "zio.telemetry.opentelemetry.example.ProxyApp"

  override def run: Task[Nothing] =
    (for {
      server        <- ZIO.service[ProxyHttpServer]
      openTelemetry <- ZIO.service[OpenTelemetry]
      _             <- ZIO.attempt(OpenTelemetryAppender.install(openTelemetry.unsafe.asJava))
      exitCode      <- server.start
    } yield exitCode).provide(
      configLayer,
      Client.default,
      BackendClient.live,
      ProxyHttpServer.live,
      ProxyHttpApp.live,
      Otel.global(),
      Otel.tracer(instrumentationScopeName)
    )

}
