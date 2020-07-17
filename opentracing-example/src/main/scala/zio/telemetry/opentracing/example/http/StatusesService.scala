package zio.telemetry.opentracing.example.http

import io.circe.Encoder
import io.opentracing.tag.Tags
import org.http4s.circe.jsonEncoderOf
import org.http4s.dsl.Http4sDsl
import org.http4s.{ EntityEncoder, HttpRoutes, Response }
import sttp.model.Uri
import zio.clock.Clock
import zio.telemetry.opentracing.OpenTracing
import zio.telemetry.opentracing.example.HttpOperation
import zio.interop.catz._
import zio.{ FiberRef, ZIO, ZLayer }

object StatusesService {

  def statuses(
    backendUri: Uri,
    service: ZLayer[Clock, Throwable, Clock with OpenTracing with Client],
    ref: FiberRef[HttpOperation]
  ): HttpRoutes[AppTask] = {
    val dsl: Http4sDsl[AppTask] = Http4sDsl[AppTask]
    import dsl._

    implicit def encoder[A: Encoder]: EntityEncoder[AppTask, A] = jsonEncoderOf[AppTask, A]

    def statuses: ZIO[Clock with OpenTracing with Client, Throwable, Response[AppTask]] =
      for {
        _   <- OpenTracing.tag(Tags.SPAN_KIND.getKey, Tags.SPAN_KIND_CLIENT)
        _   <- OpenTracing.tag(Tags.HTTP_METHOD.getKey, GET.name)
        _   <- OpenTracing.setBaggageItem("proxy-baggage-item-key", "proxy-baggage-item-value")
        res <- Client.status(backendUri.path("status")).flatMap(Ok(_))
      } yield res

    HttpRoutes.of[AppTask] {
      case req @ GET -> Root / "statuses" =>
        ref.locally(HttpOperation("/statuses", req.headers)) {
          statuses.provideLayer(service)
        }
    }
  }

}
