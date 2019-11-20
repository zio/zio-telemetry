package zio.telemetry.example.http

import io.circe.Encoder
import io.opentracing.propagation.Format.Builtin.{ HTTP_HEADERS => HttpHeadersFormat }
import io.opentracing.propagation.TextMapAdapter
import org.http4s.circe.jsonEncoderOf
import org.http4s.dsl.Http4sDsl
import org.http4s.{ EntityEncoder, HttpRoutes }
import sttp.model.Uri
import zio.ZManaged
import zio.clock.Clock
import zio.interop.catz._
import zio.telemetry.opentracing.OpenTracing

import scala.collection.mutable
import scala.jdk.CollectionConverters._

object StatusesService {

  def statuses(backendUri: Uri, service: ZManaged[Clock, Throwable, Clock with OpenTracing]): HttpRoutes[AppTask] = {
    val dsl: Http4sDsl[AppTask] = Http4sDsl[AppTask]
    import dsl._

    implicit def encoder[A: Encoder]: EntityEncoder[AppTask, A] = jsonEncoderOf[AppTask, A]

    HttpRoutes.of[AppTask] {
      case GET -> Root / "statuses" =>
        service.use { env =>
          (for {
            _ <- env.telemetry.root("/statuses")
            _ <- OpenTracing.tag("proxy-tag", "proxy-tag-value")
            _ <- OpenTracing.inject(HttpHeadersFormat, new TextMapAdapter(mutable.Map.empty[String, String].asJava))
            res <- Client
                    .status(backendUri.path("status"))
                    .map(_.body)
                    .flatMap {
                      case Right(s) => Ok(Statuses(List(s, StatusUp)))
                      case _        => Ok(Statuses(List(Status("backend", "1.0.0", "down"), StatusUp)))
                    }
          } yield res).provide(env)
        }
    }
  }

  private val StatusUp = Status("proxy", "1.0.0", "up")

}
