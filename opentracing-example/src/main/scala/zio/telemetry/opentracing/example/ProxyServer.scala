package zio.telemetry.opentracing.example

import sttp.model.Uri
import zhttp.service.server.ServerChannelFactory
import zhttp.service.{ EventLoopGroup, Server, ServerChannelFactory }
import zio.Console.printLine
import zio.config.getConfig
import zio.config.magnolia.DeriveConfigDescriptor.descriptor
import zio.config.typesafe.TypesafeConfig
import zio.telemetry.opentracing.example.JaegerTracer.makeService
import zio.telemetry.opentracing.example.config.AppConfig
import zio.telemetry.opentracing.example.http.ProxyApp
import zio.{ ExitCode, ZEnv, ZIO, ZIOAppDefault, ZLayer }

object ProxyServer extends ZIOAppDefault {

  private val configLayer = TypesafeConfig.fromResourcePath(descriptor[AppConfig])

  private val appEnv: ZLayer[ZEnv, Throwable, AppConfig with EventLoopGroup with ServerChannelFactory] =
    configLayer ++ ServerChannelFactory.auto ++ EventLoopGroup.auto(0)

  override def run: ZIO[ZEnv, Nothing, ExitCode] = {
    val exit =
      getConfig[AppConfig].flatMap { conf =>
        val service = makeService(conf.tracer.host, "zio-proxy")
        for {
          backendUrl <- ZIO.fromEither(Uri.safeApply(conf.backend.host, conf.backend.port))
          result     <- (Server.port(conf.proxy.port) ++ Server.app(ProxyApp.statuses(backendUrl, service))).make
                          .use(_ => printLine(s"ProxyServer started on ${conf.proxy.port}") *> ZIO.never)
                          .exitCode
        } yield result
      }

    exit.provideSomeLayer(appEnv) orElse ZIO.succeed(ExitCode.failure)
  }
}
