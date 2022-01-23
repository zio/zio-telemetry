package zio.telemetry.opentelemetry.example

import sttp.model.Uri
import zhttp.service.server.ServerChannelFactory
import zhttp.service.{EventLoopGroup, Server, ServerChannelFactory}
import zio.Console.printLine
import zio.config.getConfig
import zio.config.magnolia.{Descriptor, descriptor}
import zio.config.typesafe.TypesafeConfig
import zio.telemetry.opentelemetry.Tracing
import zio.telemetry.opentelemetry.example.config.AppConfig
import zio.telemetry.opentelemetry.example.http.BackendApp
import zio.{ZEnv, ZIO, ZIOAppDefault, ZLayer}

object BackendServer extends ZIOAppDefault {
  implicit val sttpUriDescriptor: Descriptor[Uri] =
    Descriptor[String].transformOrFailLeft(Uri.parse)(_.toString)

  val server =
    getConfig[AppConfig].flatMap { conf =>
      val port = conf.backend.host.port.getOrElse(9000)
      (Server.port(port) ++ Server.app(BackendApp.routes)).make.use(_ =>
        printLine(s"BackendServer started on port $port") *> ZIO.never
      )
    }

  val configLayer = TypesafeConfig.fromResourcePath(descriptor[AppConfig])

  val appLayer: ZLayer[ZEnv with AppConfig, Throwable, Tracing with ServerChannelFactory with EventLoopGroup] =
    (JaegerTracer.live >>> Tracing.live) ++ ServerChannelFactory.auto ++ EventLoopGroup.auto(0)

  override def run =
    server.provideSomeLayer(configLayer >+> appLayer).exitCode
}
