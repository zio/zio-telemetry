package zio.telemetry.opentelemetry.example.http

import sttp.client3._
import sttp.client3.ziojson._
import sttp.model.Uri
import zio.telemetry.opentelemetry.example.config.AppConfig
import zio._
import zio.telemetry.opentelemetry.example.Backend

case class Client(backend: Backend, config: AppConfig) {

  private val backendUrl =
    Uri
      .safeApply(config.backend.host, config.backend.port)
      .map(_.withPath("status"))
      .left
      .map(new IllegalArgumentException(_))

  def status(headers: Map[String, String]): Task[Statuses] =
    for {
      url      <- ZIO.fromEither(backendUrl)
      response <- backend
                    .send(
                      basicRequest
                        .get(url.withPath("status"))
                        .headers(headers)
                        .response(asJson[Status])
                    )
      status    = response.body.getOrElse(Status.down("backend"))
    } yield Statuses(List(status, Status.up("proxy")))

}

object Client {

  val live: RLayer[AppConfig with Backend, Client] =
    ZLayer.fromFunction(Client.apply _)

}
