package zio.telemetry.opentracing.example

import cats.effect.{ExitCode => catsExitCode}
import org.http4s.server.Router
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.syntax.kleisli._
import zio.config.typesafe.TypesafeConfig
import zio.config.getConfig
import zio.config.magnolia.DeriveConfigDescriptor.descriptor
import zio.{ExitCode, ZEnv, ZIO}
import zio.clock.Clock
import zio.interop.catz._
import zio.telemetry.opentracing.example.JaegerTracer.makeService
import zio.telemetry.opentracing.example.config.AppConfig
import zio.telemetry.opentracing.example.http.{AppTask, StatusService}

object BackendServer extends CatsApp {

  private val configLayer = TypesafeConfig.fromDefaultLoader(descriptor[AppConfig])

  override def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] = {
    val exit =
      ZIO.runtime[Clock].flatMap { implicit runtime =>
        implicit val ec = runtime.platform.executor.asEC
        for {
          conf   <- getConfig[AppConfig].provideLayer(configLayer)
          service = makeService(conf.tracer.host, "zio-backend")
          router  = Router[AppTask]("/" -> StatusService.status(service)).orNotFound
          result <- BlazeServerBuilder[AppTask](ec)
                      .bindHttp(conf.backend.port, conf.backend.host)
                      .withHttpApp(router)
                      .serve
                      .compile[AppTask, AppTask, catsExitCode]
                      .drain
                      .as(ExitCode.success)
        } yield result
      }
    exit.orElse(ZIO.succeed(ExitCode.failure))
  }
}
