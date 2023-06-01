package zio.telemetry.opentelemetry.instrumentation.example

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Tracer
import zio._
import zio.config.ReadError
import zio.config.typesafe.TypesafeConfig
import zio.config.magnolia._
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.instrumentation.example.config.AppConfig
import zio.telemetry.opentelemetry.instrumentation.example.http.{HttpServer, HttpServerApp}
import zio.telemetry.opentelemetry.tracing.Tracing

object ServerApp extends ZIOAppDefault {

  private val configLayer: Layer[ReadError[String], AppConfig] =
    TypesafeConfig.fromResourcePath(descriptor[AppConfig])

  private val globalTracerLayer: TaskLayer[Tracer] =
    ZLayer.fromZIO(
      ZIO.attempt(GlobalOpenTelemetry.getTracer("zio.telemetry.opentelemetry.instrumentation.example.ServerApp"))
    )

  override def run: Task[ExitCode] =
    ZIO
      .serviceWithZIO[HttpServer](_.start.exitCode)
      .provide(
        configLayer,
        HttpServer.live,
        HttpServerApp.live,
        Tracing.live,
        globalTracerLayer,
        ContextStorage.openTelemetryContext
      )

}
