package zio.telemetry.opentelemetry.http

import io.circe.Encoder
import io.opentelemetry.trace.Span
import org.http4s.circe.jsonEncoderOf
import org.http4s.dsl.Http4sDsl
import org.http4s.{ EntityEncoder, HttpRoutes }
import sttp.model.Uri
import zio.{ UIO, ULayer }
import zio.clock.Clock
import zio.opentelemetry.{ CurrentSpan, OpenTelemetry }
import zio.opentelemetry.SpanSyntax._
import zio.interop.catz._
import zio.opentelemetry.attributevalue.AttributeValueConverterInstances._

import scala.collection.mutable

object StatusesService {

  def statuses(backendUri: Uri, service: ULayer[Clock with OpenTelemetry]): HttpRoutes[AppTask] = {
    val dsl: Http4sDsl[AppTask] = Http4sDsl[AppTask]
    import dsl._

    implicit def encoder[A: Encoder]: EntityEncoder[AppTask, A] = jsonEncoderOf[AppTask, A]

    val setter = (carrier: mutable.Map[String, String], key: String, value: String) => carrier.update(key, value)

    HttpRoutes.of[AppTask] {
      case GET -> Root / "statuses" =>
        val zio =
          for {
            _              <- CurrentSpan.setAttribute("http.method", "get")
            _              <- CurrentSpan.addEvent("proxy-event")
            httpTextFormat <- UIO(io.opentelemetry.OpenTelemetry.getPropagators.getHttpTextFormat)
            carrier        <- UIO(mutable.Map[String, String]().empty)
            _              <- CurrentSpan.injectCurrentSpan(httpTextFormat, carrier, setter)
            headers        <- UIO(carrier.toMap)
            up             = Status.up("proxy")
            res <- Client
                    .status(backendUri.path("status"), headers)
                    .map(_.body)
                    .flatMap {
                      case Right(s) => Ok(Statuses(List(s, up)))
                      case _        => Ok(Statuses(List(Status.down("backend"), up)))
                    }
          } yield res

        zio.rootSpan("/statuses", Span.Kind.SERVER).provideLayer(service)
    }
  }

}
