package zio.telemetry.example

import cats.effect.ExitCode
import org.http4s.server.Router
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.syntax.kleisli._
import sttp.model.Uri
import zio.interop.catz._
import zio.telemetry.example.JaegerTracer.makeService
import zio.telemetry.example.config.Configuration
import zio.telemetry.example.http.{ AppTask, StatusesService }
import zio.{ ZEnv, ZIO }

object ProxyServer extends CatsApp {

  override def run(args: List[String]): ZIO[ZEnv, Nothing, Int] =
    (for {
      conf       <- config.load.provide(Configuration.Live)
      service    = makeService(conf.tracer.host, "zio-proxy")
      backendUrl <- ZIO.fromEither(Uri.safeApply(conf.backend.host, conf.backend.port))
      router     = Router[AppTask]("/" -> StatusesService.statuses(backendUrl, service)).orNotFound
      result <- BlazeServerBuilder[AppTask]
                 .bindHttp(conf.proxy.port, conf.proxy.host)
                 .withHttpApp(router)
                 .serve
                 .compile[AppTask, AppTask, ExitCode]
                 .drain
                 .as(0)
    } yield result) orElse ZIO.succeed(1)

}
