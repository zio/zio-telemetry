package zio.telemetry.opentelemetry.example.http

import io.circe.Encoder
import io.circe.syntax._
import io.opentelemetry.OpenTelemetry
import io.opentelemetry.context.propagation.HttpTextFormat
import io.opentelemetry.context.propagation.HttpTextFormat.Getter
import io.opentelemetry.trace.Span
import org.http4s._
import org.http4s.circe.jsonEncoderOf
import org.http4s.dsl.Http4sDsl
import org.http4s.util.CaseInsensitiveString
import zio.interop.catz._
import zio.telemetry.opentelemetry.Tracing
import zio.telemetry.opentelemetry.TracingSyntax._
import zio.telemetry.opentelemetry.example.http.{ Status => ServiceStatus }

object StatusService {

  val dsl: Http4sDsl[AppTask] = Http4sDsl[AppTask]
  import dsl._

  implicit def encoder[A: Encoder]: EntityEncoder[AppTask, A] = jsonEncoderOf[AppTask, A]

  val httpTextFormat: HttpTextFormat = OpenTelemetry.getPropagators.getHttpTextFormat
  val getter: Getter[Headers]        = (carrier, key) => carrier.get(CaseInsensitiveString(key)).map(_.value).orNull

  val routes: HttpRoutes[AppTask] = HttpRoutes.of[AppTask] { case request @ GET -> Root / "status" =>
    val response = for {
      _        <- Tracing.addEvent("event from backend before response")
      response <- Ok(ServiceStatus.up("backend").asJson)
      _        <- Tracing.addEvent("event from backend after response")
    } yield response

    response.spanFrom(httpTextFormat, request.headers, getter, "/status", Span.Kind.SERVER)

  }

}
