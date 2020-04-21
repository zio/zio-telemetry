package zio.telemetry.opentelemetry.example.http

import io.circe.Encoder
import io.opentelemetry.OpenTelemetry
import io.opentelemetry.context.propagation.HttpTextFormat.Setter
import io.opentelemetry.trace.{ Span, Status => TraceStatus }
import org.http4s.circe.jsonEncoderOf
import org.http4s.dsl.Http4sDsl
import org.http4s.{ EntityEncoder, HttpRoutes }
import zio.interop.catz._
import zio.telemetry.opentelemetry.Tracing.root
import zio.telemetry.opentelemetry.attributevalue.AttributeValueConverterInstances._
import zio.telemetry.opentelemetry.Tracing

import scala.collection.mutable

object StatusesService {

  val dsl: Http4sDsl[AppTask] = Http4sDsl[AppTask]
  import dsl._

  implicit def encoder[A: Encoder]: EntityEncoder[AppTask, A] = jsonEncoderOf[AppTask, A]

  val httpTextFormat                              = OpenTelemetry.getPropagators.getHttpTextFormat
  val carrier                                     = mutable.Map[String, String]().empty
  val setter: Setter[mutable.Map[String, String]] = (carrier, key, value) => carrier.update(key, value)

  val errorMapper: PartialFunction[Throwable, TraceStatus] = { case _ => TraceStatus.UNKNOWN }

  val routes: HttpRoutes[AppTask] = HttpRoutes.of[AppTask] {
    case GET -> Root / "statuses" =>
      root("/statuses", Span.Kind.SERVER, errorMapper) {
        for {
          _       <- Tracing.setAttribute("http.method", "get")
          _       <- Tracing.addEvent("proxy-event")
          _       <- Tracing.inject(httpTextFormat, carrier, setter)
          headers = carrier.toMap

          res <- Client.status(headers).flatMap(Ok(_))

        } yield res
      }
  }

}
