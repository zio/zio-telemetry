package zio.telemetry.opentracing.example

import zio.config.magnolia._
import zio.config.typesafe.TypesafeConfig
import zio.telemetry.opentracing.example.config.AppConfig
import zio.telemetry.opentracing.example.http.{BackendHttpApp, BackendHttpServer}
import zio._
import zio.telemetry.opentracing.OpenTracing

object BackendApp extends ZIOAppDefault {

  private val configLayer =
    TypesafeConfig.fromResourcePath(descriptor[AppConfig])

  override def run: ZIO[Environment with ZIOAppArgs with Scope, Any, Any] =
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
