package zio.telemetry.opentracing.example.http

import io.circe.Encoder
import io.circe.syntax._
import org.http4s._
import org.http4s.circe.jsonEncoderOf
import org.http4s.dsl.Http4sDsl
import zio.clock.Clock
import zio.interop.catz._
import zio.telemetry.opentracing.example.http.{Status => ServiceStatus}
import zio.telemetry.opentracing._
import zio.{FiberRef, ZLayer}
import zio.telemetry.opentracing.example.HttpOperation

object StatusService {

  val dsl: Http4sDsl[AppTask] = Http4sDsl[AppTask]
  import dsl._

  implicit def encoder[A: Encoder]: EntityEncoder[AppTask, A] = jsonEncoderOf[AppTask, A]

  def status(service: ZLayer[Clock, Throwable, Clock with OpenTracing], ref: FiberRef[HttpOperation]): HttpRoutes[AppTask] =
    HttpRoutes.of[AppTask] {
      case request @ GET -> Root / "status" =>
        ref.locally(HttpOperation("/status", request.headers)) {
          Ok(ServiceStatus.up("backend").asJson).provideLayer(service)
        }
    }

}
