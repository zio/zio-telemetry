package zio.telemetry.opentelemetry.http

import io.circe.Encoder
import io.circe.syntax._
import io.opentelemetry.context.propagation.HttpTextFormat
import io.opentelemetry.trace.Span
import org.http4s._
import org.http4s.circe.jsonEncoderOf
import org.http4s.dsl.Http4sDsl
import zio.clock.Clock
import zio.interop.catz._
import zio.opentelemetry.OpenTelemetry
import zio.opentelemetry.SpanSyntax._
import zio.telemetry.example.http.{ Status => ServiceStatus }
import zio.{ UIO, ULayer, ZIO }

import scala.collection.mutable

object StatusService {

  val dsl: Http4sDsl[AppTask] = Http4sDsl[AppTask]
  import dsl._

  implicit def encoder[A: Encoder]: EntityEncoder[AppTask, A] = jsonEncoderOf[AppTask, A]

  val httpTextFormat: HttpTextFormat = io.opentelemetry.OpenTelemetry.getPropagators.getHttpTextFormat
  val getter: (mutable.Map[String, String], String) => Option[String] =
    (carrier: mutable.Map[String, String], key: String) => carrier.get(key)

  def status(service: ULayer[Clock with OpenTelemetry]): HttpRoutes[AppTask] =
    HttpRoutes.of[AppTask] {
      case request @ GET -> Root / "status" =>
        val headers = mutable.Map(request.headers.toList.map(h => h.name.value -> h.value): _*)

        val response: ZIO[OpenTelemetry with Clock, Throwable, Response[AppTask]] = for {
          _        <- UIO.unit.addEvent("event from backend before response")
          response <- Ok(ServiceStatus.up("backend").asJson)
          _        <- UIO.unit.addEvent("event from backend after response")
        } yield response

        response.spanFrom(httpTextFormat, headers, getter, "/status", Span.Kind.SERVER).provideLayer(service)

    }

}
