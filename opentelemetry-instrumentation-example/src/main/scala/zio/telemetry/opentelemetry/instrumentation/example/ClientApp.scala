package zio.telemetry.opentelemetry.instrumentation.example

import sttp.client3.httpclient.zio.HttpClientZioBackend
import zio.config.magnolia.descriptor
import zio.config.typesafe.TypesafeConfig
import zio.telemetry.opentelemetry.instrumentation.example.config.AppConfig
import zio._
import zio.config.ReadError
import zio.telemetry.opentelemetry.instrumentation.example.http.HttpClient

object ClientApp extends ZIOAppDefault {

  private val configLayer: Layer[ReadError[String], AppConfig] =
    TypesafeConfig.fromResourcePath(descriptor[AppConfig])

  private val httpBackendLayer: TaskLayer[Backend] =
    ZLayer.scoped {
      ZIO.acquireRelease(HttpClientZioBackend())(_.close().ignore)
    }

  override def run: Task[ExitCode] =
    ZIO
      .serviceWithZIO[HttpClient](_.health.exitCode)
      .provide(
        configLayer,
        httpBackendLayer,
        HttpClient.live
      )
}
