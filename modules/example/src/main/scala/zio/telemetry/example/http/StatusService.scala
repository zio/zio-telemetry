package zio.telemetry.example.http

import io.circe.Encoder
import io.circe.syntax._
import io.opentracing.propagation.Format.Builtin.{ HTTP_HEADERS => HttpHeadersFormat }
import io.opentracing.propagation.TextMapAdapter
import org.http4s._
import org.http4s.circe.jsonEncoderOf
import org.http4s.dsl.Http4sDsl
import zio.clock.Clock
import zio.interop.catz._
import zio.telemetry.example.http.{ Status => ServiceStatus }
import zio.telemetry.opentracing._
import zio.{ ZIO, ZManaged }

import scala.jdk.CollectionConverters._

object StatusService {

  val dsl: Http4sDsl[AppTask] = Http4sDsl[AppTask]
  import dsl._

  implicit def encoder[A: Encoder]: EntityEncoder[AppTask, A] = jsonEncoderOf[AppTask, A]

  def status(service: ZManaged[Clock, Throwable, Clock with OpenTracing]): HttpRoutes[AppTask] =
    HttpRoutes.of[AppTask] {
      case request @ GET -> Root / "status" =>
        val headers = request.headers.toList.map(h => h.name.value -> h.value).toMap
        service.use { env =>
          ZIO.unit
            .spanFrom(HttpHeadersFormat, new TextMapAdapter(headers.asJava), "/status")
            .provide(env) *> Ok(ServiceStatus.up("backend").asJson)
        }
    }

}
