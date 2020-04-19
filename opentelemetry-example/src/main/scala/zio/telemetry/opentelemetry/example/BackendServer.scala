package zio.telemetry.opentelemetry.example

import cats.effect.ExitCode
import org.http4s.server.Router
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.syntax.kleisli._
import zio.interop.catz._
import zio.interop.catz.implicits._
import zio.telemetry.opentelemetry.example.config.Configuration
import zio.telemetry.opentelemetry.example.http.StatusService
import JaegerTracer.makeService
import zio.{ Task, ZEnv, ZIO }

object BackendServer extends CatsApp {

  override def run(args: List[String]): ZIO[ZEnv, Nothing, Int] =
    (for {
      conf    <- Configuration.load.provideLayer(Configuration.live)
      service = makeService(conf.tracer.host, "zio-backend")
      router  = Router[Task]("/" -> StatusService.status(service)).orNotFound
      result <- BlazeServerBuilder[Task]
                 .bindHttp(conf.backend.port, conf.backend.host)
                 .withHttpApp(router)
                 .serve
                 .compile[Task, Task, ExitCode]
                 .drain
                 .as(0)
    } yield result).orElse(ZIO.succeed(1))
}
