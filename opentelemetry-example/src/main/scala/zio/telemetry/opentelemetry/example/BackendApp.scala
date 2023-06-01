package zio.telemetry.opentelemetry.example

import zio.config.magnolia._
import zio.config.typesafe.TypesafeConfig
import zio.telemetry.opentelemetry.example.config.AppConfig
import zio.telemetry.opentelemetry.example.http.{BackendHttpApp, BackendHttpServer}
import zio._
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.tracing.Tracing

object BackendApp extends ZIOAppDefault {

  private val configLayer = TypesafeConfig.fromResourcePath(descriptor[AppConfig])

  override def run: Task[ExitCode] =
    ZIO
      .serviceWithZIO[BackendHttpServer](_.start.exitCode)
      .provide(
        configLayer,
        BackendHttpServer.live,
        BackendHttpApp.live,
        Tracing.live,
        Baggage.live(),
        ContextStorage.fiberRef,
        JaegerTracer.live
      )

}
