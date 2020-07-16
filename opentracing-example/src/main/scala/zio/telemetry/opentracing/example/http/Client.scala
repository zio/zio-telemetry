package zio.telemetry.opentracing.example.http

import sttp.client._
import sttp.client.circe.asJson
import sttp.model.Uri
import zio._
import zio.telemetry.opentracing._

object Client {

  trait Service {
    def status(uri: Uri): RIO[OpenTracing, Statuses]
  }

  def status(uri: Uri): RIO[OpenTracing with Client, Statuses] =
    ZIO.accessM(_.get[Client.Service].status(uri))

  val live: ZLayer[TracedBackend, Nothing, Client] = ZLayer.fromService { implicit backend =>
    new Service {
      val up = Status.up("proxy")

      def status(uri: Uri): RIO[OpenTracing, Statuses] =
        basicRequest.get(uri).response(asJson[Status]).send().map(_.body).map {
          case Right(s) => Statuses(List(s, up))
          case _        => Statuses(List(Status.down("backend"), up))
        }
    }
  }
}
