package zio.telemetry.opentelemetry.example.http

import zio._
import zio.http.{ Header, Headers, Request, URL }
import zio.json._
import zio.telemetry.opentelemetry.example.config.AppConfig

import java.nio.charset.StandardCharsets

case class Client(backend: zio.http.Client, config: AppConfig) {

  private val backendUrl =
    URL
      .decode(s"http://${config.backend.host}:${config.backend.port}")
      .left
      .map(new IllegalArgumentException(_))

  def status(headers: Map[String, String]): Task[Statuses] =
    for {
      url      <- ZIO.fromEither(backendUrl)
      request   = Request
                    .get(url.withPath("status"))
                    .copy(headers = Headers(headers.map(x => Header.Custom(x._1, x._2))))
      response <- backend.request(request)
      json     <- response.body.asString(StandardCharsets.UTF_8)
      status   <- ZIO
                    .fromEither(JsonDecoder[Status].decodeJson(json))
                    .catchAll(_ => ZIO.succeed(Status.down("backend")))
    } yield Statuses(List(status, Status.up("proxy")))

}

object Client {

  val live: RLayer[AppConfig with zio.http.Client, Client] =
    ZLayer.fromFunction(Client.apply _)

}
