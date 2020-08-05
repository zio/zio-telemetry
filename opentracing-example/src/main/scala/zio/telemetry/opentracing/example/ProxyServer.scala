package zio.telemetry.opentracing.example

import cats.effect.{ ExitCode => catsExitCode }
import org.http4s.server.Router
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.syntax.kleisli._
import sttp.model.Uri
import zio.{ ExitCode, ZEnv, ZIO }
import zio.clock.Clock
import zio.interop.catz._
import zio.telemetry.opentracing.example.JaegerTracer.makeService
import zio.telemetry.opentracing.example.config.Configuration
import zio.telemetry.opentracing.example.http.{ AppTask, StatusesService }

object ProxyServer extends CatsApp {

  override def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] = {
    val exit =
      ZIO.runtime[Clock].flatMap { implicit runtime =>
        implicit val ec = runtime.platform.executor.asEC
        for {
          conf       <- Configuration.load.provideLayer(Configuration.live)
          service    = makeService(conf.tracer.host, "zio-proxy")
          backendUrl <- ZIO.fromEither(Uri.safeApply(conf.backend.host, conf.backend.port))
          router     = Router[AppTask]("/" -> StatusesService.statuses(backendUrl, service)).orNotFound
          result <- BlazeServerBuilder[AppTask](ec)
                     .bindHttp(conf.proxy.port, conf.proxy.host)
                     .withHttpApp(router)
                     .serve
                     .compile[AppTask, AppTask, catsExitCode]
                     .drain
                     .as(ExitCode.success)
        } yield result
      }
    exit orElse ZIO.succeed(ExitCode.failure)
  }
}
