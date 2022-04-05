package zio.telemetry.opentracing.example

import sttp.model.Uri
import zhttp.service.server.ServerChannelFactory
import zhttp.service.{EventLoopGroup, Server, ServerChannelFactory}
import zio.Console.printLine
import zio.config.getConfig
import zio.config.magnolia._
import zio.config.typesafe.TypesafeConfig
import zio.telemetry.opentracing.example.JaegerTracer.makeService
import zio.telemetry.opentracing.example.config.AppConfig
import zio.telemetry.opentracing.example.http.ProxyApp
import zio.{ZIO, ZIOAppDefault}

object ProxyServer extends ZIOAppDefault {

  private val configLayer = TypesafeConfig.fromResourcePath(descriptor[AppConfig])

  type AppEnv = AppConfig with EventLoopGroup with ServerChannelFactory

  private val appEnv =
    configLayer ++ ServerChannelFactory.auto ++ EventLoopGroup.auto(0)

  val server: ZIO[AppEnv, Throwable, Unit] =
    ZIO.scoped[AppEnv] {
      for {
        conf <- getConfig[AppConfig]
        backendUrl <- ZIO.fromEither(Uri.safeApply(conf.backend.host, conf.backend.port)).mapError(new IllegalArgumentException(_))
        service = makeService(conf.tracer.host, "zio-proxy")
        server = Server.port(conf.proxy.port) ++ Server.app(ProxyApp.statuses(backendUrl, service))
        _ <- server.make
        _ <- printLine(s"ProxyServer started on ${conf.proxy.port}") *> ZIO.never
      } yield ()
    }

  override def run =
    ZIO.provideLayer(appEnv)(server.exitCode)
}
