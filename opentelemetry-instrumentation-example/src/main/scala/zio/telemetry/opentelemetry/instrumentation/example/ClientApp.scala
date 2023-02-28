package zio.telemetry.opentelemetry.instrumentation.example

import sttp.client3.asynchttpclient.zio.AsyncHttpClientZioBackend
import zio.config.magnolia.descriptor
import zio.config.typesafe.TypesafeConfig
import zio.telemetry.opentelemetry.instrumentation.example.config.AppConfig
import zio._
import zio.telemetry.opentelemetry.instrumentation.example.http.HttpClient

object ClientApp extends ZIOAppDefault {

  private val configLayer = TypesafeConfig.fromResourcePath(descriptor[AppConfig])

  private val httpBackendLayer: TaskLayer[Backend] =
    ZLayer.scoped {
      ZIO.acquireRelease(AsyncHttpClientZioBackend())(_.close().ignore)
    }

  override def run: Task[ExitCode] =
    ZIO
      .serviceWithZIO[HttpClient](_.health.flatMap(r => Console.printLine(s"Health: $r")).exitCode)
      .provide(
        configLayer,
        httpBackendLayer,
        HttpClient.live
      )
}
