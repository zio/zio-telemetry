package zio.telemetry.opentracing.example

import sttp.client3.asynchttpclient.zio.AsyncHttpClientZioBackend
import zio.config.magnolia._
import zio.config.typesafe.TypesafeConfig
import zio.telemetry.opentracing.example.config.AppConfig
import zio.telemetry.opentracing.example.http.{ Client, ProxyHttpApp, ProxyHttpServer }
import zio._
import zio.telemetry.opentracing.OpenTracing

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
        OpenTracing.live(),
        JaegerTracer.live("zio-proxy")
      )

}
