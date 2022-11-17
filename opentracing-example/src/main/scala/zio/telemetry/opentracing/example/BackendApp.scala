package zio.telemetry.opentracing.example

import zio.config.magnolia._
import zio.config.typesafe.TypesafeConfig
import zio.telemetry.opentracing.example.config.AppConfig
import zio.telemetry.opentracing.example.http.{ BackendHttpApp, BackendHttpServer }
import zio._
import zio.telemetry.opentracing.OpenTracing

object BackendApp extends ZIOAppDefault {

  private val configLayer =
    TypesafeConfig.fromResourcePath(descriptor[AppConfig])

  override def run: Task[ExitCode] =
    ZIO
      .serviceWithZIO[BackendHttpServer](_.start.exitCode)
      .provide(
        configLayer,
        BackendHttpServer.live,
        BackendHttpApp.live,
        OpenTracing.live(),
        JaegerTracer.live("zio-backend")
      )

}
