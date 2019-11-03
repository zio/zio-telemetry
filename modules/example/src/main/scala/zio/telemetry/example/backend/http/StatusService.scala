package zio.telemetry.example.backend.http

import java.util.concurrent.TimeUnit

import cats.effect.Sync
import io.circe.Encoder
import io.circe.syntax._
import io.opentracing.propagation.Format.Builtin.HTTP_HEADERS
import io.opentracing.propagation.TextMapAdapter
import org.http4s._
import org.http4s.circe.jsonEncoderOf
import org.http4s.dsl.io._
import zio._
import zio.clock.Clock
import zio.duration.Duration
import zio.interop.catz._
import zio.telemetry.example.http.Status
import zio.telemetry.opentracing._

import scala.jdk.CollectionConverters._

object StatusService {

  type AppTask[A] = ZIO[Clock, Throwable, A]

  implicit def jsonEncoder[A <: Product: Encoder, F[_]: Sync]: EntityEncoder[F, A] =
    jsonEncoderOf[F, A]

  def status(service: ZManaged[Clock, Throwable, Clock with OpenTracing]): HttpRoutes[AppTask] =
    HttpRoutes.of[AppTask] {
      case request @ GET -> Root / "status" =>
        val headers = request.headers.toList.map(h => h.name.value -> h.value).toMap
        val tm      = new TextMapAdapter(headers.asJava)

        service.use { env =>
          (for {
            _      <- OpenTracing.spanFrom(HTTP_HEADERS, tm, UIO.unit, "proxy").provide(env)
            parent <- env.telemetry.currentSpan.get
            span   <- env.telemetry.span(parent, "backend")
            _      <- ZIO.sleep(Duration(3, TimeUnit.SECONDS))
            _      <- ZIO.effect(span.finish())
          } yield ()).provide(env).map(_ => Response(Ok).withEntity(StatusResponse.asJson))
        }
    }

  private val StatusResponse = Status("backend", "1.0.0", "up")
}
