package zio.telemetry.opentelemetry.example.http

import sttp.client._
import sttp.client.asynchttpclient.WebSocketHandler
import sttp.client.circe.asJson
import zio.telemetry.opentelemetry.example.config.Config
import zio.{ Task, ZIO, ZLayer }

object Client {
  type Backend = SttpBackend[Task, Nothing, WebSocketHandler]

  trait Service {
    def status(headers: Map[String, String]): Task[Statuses]
  }

  def status(headers: Map[String, String]) =
    ZIO.accessM[Client](_.get.status(headers))

  val up = Status.up("proxy")

  val live = ZLayer.fromServices((backend: Backend, conf: Config) =>
    new Service {
      def status(headers: Map[String, String]): Task[Statuses] =
        backend
          .send(
            basicRequest.get(conf.backend.host.path("status")).headers(headers).response(asJson[Status])
          )
          .map(_.body match {
            case Right(s) => Statuses(List(s, up))
            case _        => Statuses(List(Status.down("backend"), up))
          })
    }
  )
}
