package zio.telemetry.example.http

import io.circe.Encoder
import io.opentracing.propagation.Format.Builtin.{ HTTP_HEADERS => HttpHeadersFormat }
import io.opentracing.propagation.TextMapAdapter
import org.http4s.circe.jsonEncoderOf
import org.http4s.dsl.io._
import org.http4s.{ EntityEncoder, HttpRoutes, Response }
import sttp.model.Uri
import zio.ZManaged
import zio.clock.Clock
import zio.interop.catz._
import zio.telemetry.opentracing.OpenTracing

import scala.collection.mutable
import scala.jdk.CollectionConverters._

object StatusesService {

  def statuses(backendUri: Uri, service: ZManaged[Clock, Throwable, Clock with OpenTracing]): HttpRoutes[AppTask] = {

    implicit def encoder[A: Encoder]: EntityEncoder[AppTask, A] = jsonEncoderOf[AppTask, A]

    HttpRoutes.of[AppTask] {
      case GET -> Root / "statuses" =>
        service.use { env =>
          (for {
            _ <- env.telemetry.root("/statuses")
            _ <- OpenTracing.tag("proxy-tag", "proxy-tag-value")
            _ <- OpenTracing.inject(HttpHeadersFormat, new TextMapAdapter(mutable.Map.empty[String, String].asJava))
            res <- Client
                    .status(backendUri)
                    .map(_.body)
                    .map {
                      case Right(s) => Response(Ok).withEntity(Statuses(List(s, StatusUp)))
                      case _        => Response(Ok).withEntity(Statuses(List(Status("backend", "1.0.0", "down"), StatusUp)))
                    }
          } yield res).provide(env)
        }
    }
  }

  private val StatusUp = Status("proxy", "1.0.0", "up")

}
