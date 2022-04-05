package zio.telemetry.opentracing.example

import zhttp.service.server.ServerChannelFactory
import zhttp.service.{EventLoopGroup, Server, ServerChannelFactory}
import zio.Console.printLine
import zio.config.getConfig
import zio.config.magnolia._
import zio.config.typesafe.TypesafeConfig
import zio.telemetry.opentracing.example.JaegerTracer.makeService
import zio.telemetry.opentracing.example.config.AppConfig
import zio.telemetry.opentracing.example.http.BackendApp
import zio.{ZIO, ZIOAppDefault}

object BackendServer extends ZIOAppDefault {

  type AppEnv = AppConfig with EventLoopGroup with ServerChannelFactory

  val server: ZIO[AppEnv, Throwable, Unit] =
    ZIO.scoped[AppEnv] {
      for {
        conf <- getConfig[AppConfig]
        tracingService = makeService(conf.tracer.host, "zio-backend")
        server = Server.port(conf.backend.port) ++ Server.app(BackendApp.status(tracingService))
        _ <- server.make
        _ <- printLine(s"BackendServer started at ${conf.backend.port}") *> ZIO.never
      } yield ()
    }

  val configLayer = TypesafeConfig.fromResourcePath(descriptor[AppConfig])
  val appLayer = ServerChannelFactory.auto ++ EventLoopGroup.auto(0)

  override def run =
    ZIO.provideLayer(configLayer >+> appLayer)(server.exitCode)
}
