package zio.telemetry.opentelemetry.example.http

import io.circe.Encoder
import io.opentelemetry.OpenTelemetry
import io.opentelemetry.context.propagation.HttpTextFormat.Setter
import io.opentelemetry.trace.{ Span, Status => TraceStatus }
import org.http4s.circe.jsonEncoderOf
import org.http4s.dsl.Http4sDsl
import org.http4s.{ EntityEncoder, HttpRoutes }
import sttp.model.Uri
import zio.interop.catz._
import zio.telemetry.opentelemetry.Tracing.root
import zio.telemetry.opentelemetry.attributevalue.AttributeValueConverterInstances._
import zio.telemetry.opentelemetry.Tracing
import zio.{ Task, UIO, ULayer }

import scala.collection.mutable

object StatusesService {

  def statuses(backendUri: Uri, service: ULayer[Tracing]): HttpRoutes[Task] = {
    val dsl: Http4sDsl[Task] = Http4sDsl[Task]
    import dsl._

    implicit def encoder[A: Encoder]: EntityEncoder[Task, A] = jsonEncoderOf[Task, A]

    val setter: Setter[mutable.Map[String, String]] = (carrier, key, value) => carrier.update(key, value)

    val errorMapper: PartialFunction[Throwable, TraceStatus] = { case _ => TraceStatus.UNKNOWN }

    HttpRoutes.of[Task] {
      case GET -> Root / "statuses" =>
        root("/statuses", Span.Kind.SERVER, errorMapper) {
          for {
            _              <- Tracing.setAttribute("http.method", "get")
            _              <- Tracing.addEvent("proxy-event")
            httpTextFormat <- UIO(OpenTelemetry.getPropagators.getHttpTextFormat)
            carrier        <- UIO(mutable.Map[String, String]().empty)
            _              <- Tracing.inject(httpTextFormat, carrier, setter)
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
