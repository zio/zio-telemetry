package zio.telemetry.opentelemetry.example

import sttp.client3.asynchttpclient.zio.AsyncHttpClientZioBackend
import zio.config.magnolia._
import zio.config.typesafe.TypesafeConfig
import zio.telemetry.opentelemetry.Tracing
import zio.telemetry.opentelemetry.example.config.AppConfig
import zio.telemetry.opentelemetry.example.http.{ Client, ProxyHttpApp, ProxyHttpServer }
import zio._

object ProxyApp extends ZIOAppDefault {

  private val configLayer = TypesafeConfig.fromResourcePath(descriptor[AppConfig])

  private val httpBackendLayer: TaskLayer[Backend] =
    ZLayer.scoped {
      ZIO.acquireRelease(AsyncHttpClientZioBackend())(_.close().ignore)
    }

  override def run: Task[ExitCode] =
    ZIO
      .serviceWithZIO[ProxyHttpServer](_.start.exitCode)
      .provide(
        configLayer,
        httpBackendLayer,
        Client.live,
        ProxyHttpServer.live,
        ProxyHttpApp.live,
        Tracing.live,
        JaegerTracer.live
      )

}
