package zio.telemetry.example.backend

import cats.effect.ExitCode
import org.http4s.server.Router
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.syntax.kleisli._
import zio._
import zio.clock.Clock
import zio.interop.catz._
import zio.telemetry.example.JaegerTracer._
import zio.telemetry.example.backend.config.Configuration
import zio.telemetry.example.backend.http.StatusService

object Server extends CatsApp {

  type AppTask[A] = ZIO[Clock, Throwable, A]

  override def run(args: List[String]): ZIO[ZEnv, Nothing, Int] =
    (for {
      conf    <- config.load.provide(Configuration.Live)
      service = makeService(conf.tracer.host, "backend")
      router  = Router[AppTask]("/" -> StatusService.status(service)).orNotFound
      result <- BlazeServerBuilder[AppTask]
                 .bindHttp(conf.api.port, conf.api.host)
                 .withHttpApp(router)
                 .serve
                 .compile[AppTask, AppTask, ExitCode]
                 .drain
                 .as(0)
    } yield result).orElse(ZIO.succeed(1))
}
