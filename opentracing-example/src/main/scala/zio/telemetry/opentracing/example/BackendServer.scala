package zio.telemetry.opentracing.example

import zio.{ App, ExitCode, ZEnv, ZIO }
import zio.clock.Clock
import zio.console.putStrLn
import zio.magic._
import zio.config.typesafe.TypesafeConfig
import zio.config.getConfig
import zio.config.magnolia.DeriveConfigDescriptor.descriptor
import zio.telemetry.opentracing.example.JaegerTracer.makeService
import zio.telemetry.opentracing.example.config.AppConfig
import zio.telemetry.opentracing.example.http.BackendApp
import zhttp.service.{ EventLoopGroup, Server }
import zhttp.service.server.ServerChannelFactory

object BackendServer extends App {

  private val configLayer = TypesafeConfig.fromDefaultLoader(descriptor[AppConfig])

  override def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] = {
    val exit =
      ZIO.runtime[Clock].flatMap { implicit runtime =>
        getConfig[AppConfig].flatMap { conf =>
          val service = makeService(conf.tracer.host, "zio-backend")
          (Server.port(conf.backend.port) ++ Server.app(BackendApp.status(service))).make
            .use(_ => putStrLn(s"BackendServer started at ${conf.backend.port}") *> ZIO.never)
            .exitCode
        }.injectCustom(
          configLayer,
          ServerChannelFactory.auto,
          EventLoopGroup.auto(0)
        )
      }

    exit orElse ZIO.succeed(ExitCode.failure)
  }
}
