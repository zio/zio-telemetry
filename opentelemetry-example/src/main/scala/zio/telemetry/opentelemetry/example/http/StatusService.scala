package zio.telemetry.opentelemetry.example.http

import io.circe.Encoder
import io.circe.syntax._
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.TextMapPropagator
import io.opentelemetry.context.propagation.TextMapPropagator.Getter
import org.http4s._
import org.http4s.circe.jsonEncoderOf
import org.http4s.dsl.Http4sDsl
import org.http4s.util.CaseInsensitiveString
import zio.interop.catz._
import zio.telemetry.opentelemetry.Tracing
import zio.telemetry.opentelemetry.TracingSyntax._
import zio.telemetry.opentelemetry.example.http.{ Status => ServiceStatus }

import java.lang
import scala.jdk.CollectionConverters._

object StatusService {

  val dsl: Http4sDsl[AppTask] = Http4sDsl[AppTask]
  import dsl._

  implicit def encoder[A: Encoder]: EntityEncoder[AppTask, A] = jsonEncoderOf[AppTask, A]

  val propagator: TextMapPropagator = W3CTraceContextPropagator.getInstance()
  val getter: Getter[Headers] = new Getter[Headers] {
    override def keys(carrier: Headers): lang.Iterable[String] =
      carrier.toList.map(_.name.value).asJava

    override def get(carrier: Headers, key: String): String =
      carrier.get(CaseInsensitiveString(key)).map(_.value).orNull
  }

  val routes: HttpRoutes[AppTask] = HttpRoutes.of[AppTask] {
    case request @ GET -> Root / "status" =>
      val response = for {
        _        <- Tracing.addEvent("event from backend before response")
        response <- Ok(ServiceStatus.up("backend").asJson)
        _        <- Tracing.addEvent("event from backend after response")
      } yield response

      response.spanFrom(propagator, request.headers, getter, "/status", Span.Kind.SERVER)

  }

}
