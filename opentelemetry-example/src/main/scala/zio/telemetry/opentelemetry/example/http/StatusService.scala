package zio.telemetry.opentelemetry.example.http

import io.circe.Encoder
import io.circe.syntax._
import io.opentelemetry.context.propagation.HttpTextFormat
import io.opentelemetry.context.propagation.HttpTextFormat.Getter
import io.opentelemetry.trace.Span
import org.http4s._
import org.http4s.circe.jsonEncoderOf
import org.http4s.dsl.Http4sDsl
import zio.interop.catz._
import zio.telemetry.opentelemetry.Tracing
import zio.telemetry.opentelemetry.TracingSyntax._
import zio.telemetry.opentelemetry.example.http.{ Status => ServiceStatus }

import scala.collection.mutable

object StatusService {

  val dsl: Http4sDsl[AppTask] = Http4sDsl[AppTask]
  import dsl._

  implicit def encoder[A: Encoder]: EntityEncoder[AppTask, A] = jsonEncoderOf[AppTask, A]

  val httpTextFormat: HttpTextFormat              = io.opentelemetry.OpenTelemetry.getPropagators.getHttpTextFormat
  val getter: Getter[mutable.Map[String, String]] = (carrier, key) => carrier.get(key).orNull

  val routes: HttpRoutes[AppTask] = HttpRoutes.of[AppTask] {
    case request @ GET -> Root / "status" =>
      val headers = mutable.Map(request.headers.toList.map(h => h.name.value -> h.value): _*)

      val response = for {
        _        <- Tracing.addEvent("event from backend before response")
        response <- Ok(ServiceStatus.up("backend").asJson)
        _        <- Tracing.addEvent("event from backend after response")
      } yield response

      response.spanFrom(httpTextFormat, headers, getter, "/status", Span.Kind.SERVER)

  }

}
