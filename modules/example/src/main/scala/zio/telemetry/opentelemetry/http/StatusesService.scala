package zio.telemetry.opentelemetry.http

import io.circe.Encoder
import io.opentelemetry.OpenTelemetry
import io.opentelemetry.context.propagation.HttpTextFormat.Setter
import io.opentelemetry.trace.Span
import org.http4s.circe.jsonEncoderOf
import org.http4s.dsl.Http4sDsl
import org.http4s.{ EntityEncoder, HttpRoutes }
import sttp.model.Uri
import zio.{ Task, UIO, ULayer }
import zio.interop.catz._
import zio.opentelemetry.tracing.Tracing
import zio.opentelemetry.tracing.Tracing.rootSpan
import zio.interop.catz._
import zio.opentelemetry.tracing.attributevalue.AttributeValueConverterInstances._

import scala.collection.mutable

object StatusesService {

  def statuses(backendUri: Uri, service: ULayer[Tracing]): HttpRoutes[Task] = {
    val dsl: Http4sDsl[Task] = Http4sDsl[Task]
    import dsl._

    implicit def encoder[A: Encoder]: EntityEncoder[Task, A] = jsonEncoderOf[Task, A]

    val setter: Setter[mutable.Map[String, String]] = (carrier, key, value) => carrier.update(key, value)

    HttpRoutes.of[Task] {
      case GET -> Root / "statuses" =>
        rootSpan("/statuses", Span.Kind.SERVER) {
          for {
            _              <- Tracing.setAttribute("http.method", "get")
            _              <- Tracing.addEvent("proxy-event")
            httpTextFormat <- UIO(OpenTelemetry.getPropagators.getHttpTextFormat)
            carrier        <- UIO(mutable.Map[String, String]().empty)
            _              <- Tracing.injectCurrentSpan(httpTextFormat, carrier, setter)
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
        }.provideLayer(service)
    }
  }

}
