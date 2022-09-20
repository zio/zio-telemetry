package zio.telemetry.opentelemetry.example.http

import sttp.capabilities.WebSockets
import sttp.capabilities.zio.ZioStreams
import sttp.client3._
import sttp.client3.ziojson._
import zio.telemetry.opentelemetry.example.config.AppConfig
import zio.{ Task, ZIO, ZLayer }
import Client._

final case class Client(conf: AppConfig, backend: Backend) {

  def status(headers: Map[String, String]): Task[Statuses] =
    backend
      .send(
        basicRequest.get(conf.backend.host.withPath("status")).headers(headers).response(asJson[Status])
      )
      .map { response =>
        val status = response.body.getOrElse(Status.down("backend"))
        Statuses(List(status, up))
      }

}

object Client {
  type Backend = SttpBackend[Task, ZioStreams with WebSockets]

  val up = Status.up("proxy")

  val live: ZLayer[AppConfig with Backend, Throwable, Client] =
    ZLayer.fromFunction(Client.apply _)
}
