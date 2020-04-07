package zio.telemetry.opentelemetry.http

import io.circe.Encoder
import io.circe.syntax._
import io.opentelemetry.context.propagation.HttpTextFormat
import org.http4s._
import org.http4s.circe.jsonEncoderOf
import org.http4s.dsl.Http4sDsl
import zio.ZLayer
import zio.clock.Clock
import zio.interop.catz._
import zio.opentelemetry.{ CurrentSpan, OpenTelemetry }
import zio.telemetry.example.http.{ Status => ServiceStatus }

import scala.collection.mutable
import io.opentelemetry.trace.Span

object StatusService {

  val dsl: Http4sDsl[AppTask] = Http4sDsl[AppTask]
  import dsl._

  implicit def encoder[A: Encoder]: EntityEncoder[AppTask, A] = jsonEncoderOf[AppTask, A]

  val httpTextFormat: HttpTextFormat = io.opentelemetry.OpenTelemetry.getPropagators.getHttpTextFormat
  val getter: (mutable.Map[String, String], String) => Option[String] =
    (carrier: mutable.Map[String, String], key: String) => carrier.get(key)

  def status(service: ZLayer[Clock, Throwable, Clock with OpenTelemetry]): HttpRoutes[AppTask] =
    HttpRoutes.of[AppTask] {
      case request @ GET -> Root / "status" =>
        val headers = mutable.Map(request.headers.toList.map(h => h.name.value -> h.value): _*)
        (for {
          _ <- CurrentSpan.createChildFromExtracted(httpTextFormat, headers, getter, "/status", Span.Kind.SERVER)
          _ <- CurrentSpan.addEvent("event from backend")
          result <- Ok(ServiceStatus.up("backend").asJson)
          _ <- CurrentSpan.endSpan
        } yield result).provideLayer(service)
    }

}
