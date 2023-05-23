package zio.telemetry.opentracing.example.http

import zio._
import zio.http.{ Header, Headers, Request, URL }
import zio.json.JsonDecoder
import zio.telemetry.opentracing.example.Backend
import zio.telemetry.opentracing.example.config.AppConfig

case class Client(backend: Backend, config: AppConfig) {

  private val backendUrl =
    URL
      .decode(s"http://${config.backend.host}/${config.backend.port}")
      .left
      .map(new IllegalArgumentException(_))

  def status(
    headers: Map[String, String]
  ): Task[Statuses] =
    for {
      url      <- ZIO.fromEither(backendUrl)
      request   = Request
                    .get(url)
                    .copy(headers = Headers(headers.map(x => Header.Custom(x._1, x._2))))
      response <- backend.request(request)
      status   <- JsonDecoder[Status]
                    .decodeJsonStream(response.body.asStream.map(_.toChar))
                    .catchAll(_ => ZIO.succeed(Status.down("backend")))
    } yield Statuses(List(status, Status.up("proxy")))

}

object Client {

  val live: RLayer[AppConfig with Backend, Client] =
    ZLayer.fromFunction(Client.apply _)

}
