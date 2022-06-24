package zio.telemetry.opentelemetry.example

import sttp.model.Uri
import zhttp.service.server.ServerChannelFactory
import zhttp.service.{ EventLoopGroup, Server, ServerChannelFactory }
import zio.Console.printLine
import zio.config.getConfig
import zio.config.magnolia.{ descriptor, Descriptor }
import zio.config.typesafe.TypesafeConfig
import zio.telemetry.opentelemetry.Tracing
import zio.telemetry.opentelemetry.example.config.AppConfig
import zio.telemetry.opentelemetry.example.http.BackendApp
import zio.{ ZIO, ZIOAppDefault }

object BackendServer extends ZIOAppDefault {
  implicit val sttpUriDescriptor: Descriptor[Uri] =
    Descriptor[String].transformOrFailLeft(Uri.parse)(_.toString)

  type AppEnv = Tracing with ServerChannelFactory with EventLoopGroup with AppConfig

  val server =
    ZIO.scoped[AppEnv] {
      for {
        conf  <- getConfig[AppConfig]
        port   = conf.backend.host.port.getOrElse(9000)
        server = Server.port(port) ++ Server.app(BackendApp.routes)
        _     <- server.make
        _     <- printLine(s"BackendServer started on port $port") *> ZIO.never
      } yield ()
    }

  val configLayer = TypesafeConfig.fromResourcePath(descriptor[AppConfig])
  val appLayer    = (JaegerTracer.live >>> Tracing.live) ++ ServerChannelFactory.auto ++ EventLoopGroup.auto(0)

  override def run =
    ZIO.provideLayer(configLayer >+> appLayer)(server.exitCode)
}
