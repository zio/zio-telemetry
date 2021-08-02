package zio.telemetry.opentelemetry.example.http

import sttp.client._
import sttp.client.asynchttpclient.WebSocketHandler
import sttp.client.circe.asJson
import zio.telemetry.opentelemetry.example.config.AppConfig
import zio.{ Task, ZIO, ZLayer }
import zio.stream.Stream

object Client {
  type Backend = SttpBackend[Task, Stream[Throwable, Byte], WebSocketHandler]

  trait Service {
    def status(headers: Map[String, String]): Task[Statuses]
  }

  def status(headers: Map[String, String]) =
    ZIO.accessM[Client](_.get.status(headers))

  val up = Status.up("proxy")

  val live = ZLayer.fromServices((backend: Backend, conf: AppConfig) =>
    new Service {
      def status(headers: Map[String, String]): Task[Statuses] =
        backend
          .send(
            basicRequest.get(conf.backend.host.withPath("status")).headers(headers).response(asJson[Status])
          )
          .map(_.body match {
            case Right(s) => Statuses(List(s, up))
            case _        => Statuses(List(Status.down("backend"), up))
          })
    }
  )
}
