package zio.telemetry.opentracing.example.http

import zio._
import zio.http._
import zio.json.JsonDecoder
import zio.telemetry.opentracing.example.config.AppConfig
import zio.telemetry.opentracing.example.http.BackendClient.BatchedClient

import java.nio.charset.StandardCharsets

case class BackendClient(backend: BatchedClient, backendUrl: URL) {

  def status(
    headers: Map[String, String]
  ): Task[BackendStatuses] =
    for {
      response <- backend.request(
                    Request
                      .get(backendUrl / "status")
                      .copy(headers = Headers(headers.map(x => Header.Custom(x._1, x._2))))
                  )
      json     <- response.body.asString(StandardCharsets.UTF_8)
      status   <- ZIO
                    .fromEither(JsonDecoder[BackendStatus].decodeJson(json))
                    .catchAll(_ => ZIO.succeed(BackendStatus.down("backend")))
    } yield BackendStatuses(List(status, BackendStatus.up("proxy")))

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
