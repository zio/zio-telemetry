package zio.telemetry.example.proxy

import cats.effect.ExitCode
import io.circe.generic.auto._
import io.circe.syntax._
import io.circe.{ Decoder, Encoder }
import io.opentracing.Tracer
import io.opentracing.propagation.Format.Builtin.{ HTTP_HEADERS => HttpHeadersFormat }
import io.opentracing.propagation._
import org.http4s._
import org.http4s.circe._
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.dsl.io._
import org.http4s.server.Router
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.syntax.kleisli._
import zio._
import zio.clock.Clock
import zio.interop.catz._
import zio.telemetry.example.proxy.config.{ Configuration, ServiceConfig }
import zio.telemetry.example.proxy.http.StatusResponse
import zio.telemetry.opentracing._

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.ExecutionContext

object Server extends CatsApp {

  type AppTask[A] = ZIO[Clock, Throwable, A]

  def app(config: ServiceConfig, tracer: Tracer): HttpApp[AppTask] = {
    implicit def decoder[A: Decoder]: EntityDecoder[AppTask, A] = jsonOf

    implicit def encoder[A: Encoder]: EntityEncoder[AppTask, A] = jsonEncoderOf

    val routes = HttpRoutes.of[AppTask] {
      case GET -> Root / "statuses" =>
        JaegerTracing.makeService(tracer).use { env =>
          (for {
            _ <- env.telemetry.root("/statuses")
            _ <- env.telemetry.tag("proxy-tag", "proxy-tag-value")
            _ = env.telemetry.inject(HttpHeadersFormat, new TextMapAdapter(mutable.Map.empty[String, String].asJava))
            res <- BlazeClientBuilder[AppTask](ExecutionContext.global).resource.use(
                    _.expect[StatusResponse](s"${config.url}/status").map { status =>
                      val statuses =
                        List(status, StatusResponse(name = "proxy", version = "1.0.0", status = "up"))
                      Response(Ok).withEntity(statuses.asJson)
                    }
                  )
          } yield res).provide(env)
        }
    }

    Router[AppTask]("/" -> routes).orNotFound
  }

  override def run(args: List[String]): ZIO[ZEnv, Nothing, Int] =
    (for {
      conf   <- config.load.provide(Configuration.Live)
      tracer <- JaegerTracing.makeTracer(conf.jaeger)
      result <- BlazeServerBuilder[AppTask]
                 .bindHttp(conf.api.port, conf.api.host)
                 .withHttpApp(app(conf.service, tracer))
                 .serve
                 .compile[AppTask, AppTask, ExitCode]
                 .drain
                 .as(0)

    } yield result) orElse ZIO.succeed(1)

}
