package zio.telemetry.opentracing.example

import zio._
import zio.config.magnolia._
import zio.config.typesafe.TypesafeConfig
import zio.telemetry.opentracing.OpenTracing
import zio.telemetry.opentracing.example.config.AppConfig
import zio.telemetry.opentracing.example.http.{BackendHttpApp, BackendHttpServer}

object BackendApp extends ZIOAppDefault {

  private val configLayer =
    TypesafeConfig.fromResourcePath(descriptor[AppConfig])

  override def run: Task[Nothing] =
    ZIO
      .serviceWithZIO[BackendHttpServer](_.start)
      .provide(
        configLayer,
        BackendHttpServer.live,
        BackendHttpApp.live,
        OpenTracing.live(),
        JaegerTracer.live("zio-backend")
      )

}
