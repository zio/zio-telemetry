package zio.telemetry.opentracing.example

import zio.{ App, ExitCode, ZEnv, ZIO }
import zio.console.putStrLn
import sttp.model.Uri
import zio.magic._
import zio.config.typesafe.TypesafeConfig
import zio.config.getConfig
import zio.config.magnolia.DeriveConfigDescriptor.descriptor
import zio.telemetry.opentracing.example.JaegerTracer.makeService
import zio.telemetry.opentracing.example.config.AppConfig
import zio.telemetry.opentracing.example.http.StatusesService
import zhttp.service.{ EventLoopGroup, Server }
import zhttp.service.server.ServerChannelFactory

object ProxyServer extends App {

  private val configLayer = TypesafeConfig.fromDefaultLoader(descriptor[AppConfig])

  override def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] = {
    val exit =
      getConfig[AppConfig].flatMap { conf =>
        val service = makeService(conf.tracer.host, "zio-proxy")
        for {
          backendUrl <- ZIO.fromEither(Uri.safeApply(conf.backend.host, conf.backend.port))
          result     <- (Server.port(conf.proxy.port) ++ Server.app(StatusesService.statuses(backendUrl, service))).make
                          .use(_ => putStrLn(s"ProxyServer started on ${conf.proxy.port}") *> ZIO.never)
                          .exitCode
        } yield result
      }.injectCustom(
        configLayer,
        ServerChannelFactory.auto,
        EventLoopGroup.auto(0)
      )

    exit orElse ZIO.succeed(ExitCode.failure)
  }
}
