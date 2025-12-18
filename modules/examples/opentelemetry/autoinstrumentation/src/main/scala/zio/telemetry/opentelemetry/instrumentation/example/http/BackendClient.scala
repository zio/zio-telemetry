package zio.telemetry.opentelemetry.instrumentation.example.http

import zio._
import zio.http.{Request, URL, _}
import zio.telemetry.opentelemetry.instrumentation.example.config.AppConfig
import zio.telemetry.opentelemetry.instrumentation.example.http.BackendClient.BatchedClient

import java.nio.charset.StandardCharsets

case class BackendClient(backend: BatchedClient, backendUrl: URL) {

  def exampleEndpoint: Task[String] =
    for {
      response <- backend.request(Request.get(backendUrl / "example-endpoint"))
      result   <- response.body.asString(StandardCharsets.UTF_8)
    } yield result

}

object BackendClient {

  type BatchedClient = ZClient[Any, Any, Body, Throwable, Response]

  val live: RLayer[AppConfig with zio.http.Client, BackendClient] =
    ZLayer {
      for {
        client     <- ZIO.service[zio.http.Client]
        config     <- ZIO.service[AppConfig]
        backendUrl <- ZIO.fromEither(URL.decode(s"http://${config.backend.host}:${config.backend.port}"))
      } yield BackendClient(client.batched, backendUrl)
    }

}
