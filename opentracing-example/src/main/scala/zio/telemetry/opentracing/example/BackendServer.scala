package zio.telemetry.opentracing.example

import zhttp.service.server.ServerChannelFactory
import zhttp.service.{ EventLoopGroup, Server, ServerChannelFactory }
import zio.Console.printLine
import zio.config.getConfig
import zio.config.magnolia.DeriveConfigDescriptor.descriptor
import zio.config.typesafe.TypesafeConfig
import zio.telemetry.opentracing.example.JaegerTracer.makeService
import zio.telemetry.opentracing.example.config.AppConfig
import zio.telemetry.opentracing.example.http.BackendApp
import zio.{ ExitCode, ZEnv, ZIO, ZIOAppDefault, ZLayer }

object BackendServer extends ZIOAppDefault {

  private val configLayer = TypesafeConfig.fromResourcePath(descriptor[AppConfig])

  private val appEnv: ZLayer[ZEnv, Throwable, AppConfig with EventLoopGroup with ServerChannelFactory] =
    configLayer ++ ServerChannelFactory.auto ++ EventLoopGroup.auto(0)

  override def run: ZIO[ZEnv, Nothing, ExitCode] = {
    val exit = for {
      conf          <- getConfig[AppConfig]
      tracingService = makeService(conf.tracer.host, "zio-backend")
      exitCode      <- (Server.port(conf.backend.port) ++ Server.app(BackendApp.status(tracingService))).make
                         .use(_ => printLine(s"BackendServer started at ${conf.backend.port}") *> ZIO.never)
                         .exitCode
    } yield exitCode

    exit.provideSomeLayer(appEnv) orElse ZIO.succeed(ExitCode.failure)
  }
}
