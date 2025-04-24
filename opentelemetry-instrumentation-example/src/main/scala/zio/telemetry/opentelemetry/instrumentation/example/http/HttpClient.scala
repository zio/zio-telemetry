package zio.telemetry.opentelemetry.instrumentation.example.http

import zio._
import zio.http.{Request, URL, _}
import zio.telemetry.opentelemetry.instrumentation.example.config.AppConfig
import zio.telemetry.opentelemetry.instrumentation.example.http.HttpClient.BatchedClient

import java.nio.charset.StandardCharsets

case class HttpClient(backend: BatchedClient, backendUrl: URL) {

  def health: Task[String] =
    for {
      response <- backend.request(Request.get(backendUrl / "health"))
      result   <- response.body.asString(StandardCharsets.UTF_8)
    } yield result

}

object HttpClient {

  type BatchedClient = ZClient[Any, Any, Body, Throwable, Response]

  val live: RLayer[AppConfig with zio.http.Client, HttpClient] =
    ZLayer {
      for {
        client     <- ZIO.service[zio.http.Client]
        config     <- ZIO.service[AppConfig]
        backendUrl <- ZIO.fromEither(URL.decode(s"http://${config.server.host}:${config.server.port}"))
      } yield HttpClient(client.batched, backendUrl)
    }

}
