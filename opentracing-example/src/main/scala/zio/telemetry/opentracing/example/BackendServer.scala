package zio.telemetry.opentracing.example

import cats.effect.ExitCode
import org.http4s.server.Router
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.syntax.kleisli._
import zio.interop.catz._
import zio.telemetry.opentracing.example.JaegerTracer.makeService
import zio.telemetry.opentracing.example.config.Configuration
import zio.telemetry.opentracing.example.http.{ AppTask, StatusService }
import zio.{ ZEnv, ZIO }

object BackendServer extends CatsApp {

  override def run(args: List[String]): ZIO[ZEnv, Nothing, Int] =
    (for {
      conf    <- Configuration.load.provideLayer(Configuration.live)
      service = makeService(conf.tracer.host, "zio-backend")
      router  = Router[AppTask]("/" -> StatusService.status(service)).orNotFound
      result <- BlazeServerBuilder[AppTask]
                 .bindHttp(conf.backend.port, conf.backend.host)
                 .withHttpApp(router)
                 .serve
                 .compile[AppTask, AppTask, ExitCode]
                 .drain
                 .as(0)
    } yield result).orElse(ZIO.succeed(1))
}
