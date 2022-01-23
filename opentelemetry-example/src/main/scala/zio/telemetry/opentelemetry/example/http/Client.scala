package zio.telemetry.opentelemetry.example.http

import sttp.capabilities.WebSockets
import sttp.capabilities.zio.ZioStreams
import sttp.client3._
import sttp.client3.ziojson._
import zio.telemetry.opentelemetry.example.config.AppConfig
import zio.{Task, ZIO, ZLayer}

object Client {
  type Backend = SttpBackend[Task, ZioStreams with WebSockets]

  trait Service {
    def status(headers: Map[String, String]): Task[Statuses]
  }

  def status(headers: Map[String, String]) =
    ZIO.environmentWithZIO[Client](_.get.status(headers))

  val up = Status.up("proxy")

  val live: ZLayer[AppConfig with Backend, Throwable, Service] = (for {
    conf <- ZIO.service[AppConfig]
    backend <- ZIO.service[Backend]
    service = new Service {
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
  } yield service).toLayer
}
