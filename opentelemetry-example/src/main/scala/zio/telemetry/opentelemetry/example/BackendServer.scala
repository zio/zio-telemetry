package zio.telemetry.opentelemetry.example

import sttp.model.Uri
import zhttp.service.server.ServerChannelFactory
import zhttp.service.{EventLoopGroup, Server, ServerChannelFactory}
import zio.Console.printLine
import zio.config.{ReadError, getConfig}
import zio.config.magnolia.{Descriptor, descriptor}
import zio.config.typesafe.TypesafeConfig
import zio.telemetry.opentelemetry.Tracing
import zio.telemetry.opentelemetry.example.config.AppConfig
import zio.telemetry.opentelemetry.example.http.BackendApp
import zio.{ExitCode, Layer, ZIO, ZIOAppDefault, ZLayer, RLayer, Task, URLayer, RIO}
import BackendServer.AppEnv

final case class BackendServer(backendApp: BackendApp) {

  val server: RIO[AppEnv, Unit] =
    ZIO.scoped[AppEnv] {
      for {
        conf  <- getConfig[AppConfig]
        port   = conf.backend.host.port.getOrElse(9000)
        server = Server.port(port) ++ Server.app(backendApp.routes)
        _     <- server.make
        _     <- printLine(s"BackendServer started on port $port") *> ZIO.never
      } yield ()
    }
}

object BackendServer extends ZIOAppDefault {

  implicit val sttpUriDescriptor: Descriptor[Uri] =
    Descriptor[String].transformOrFailLeft(Uri.parse)(_.toString)

  type AppEnv = Tracing with ServerChannelFactory with EventLoopGroup with AppConfig

  val configLayer: Layer[ReadError[String], AppConfig] = TypesafeConfig.fromResourcePath(descriptor[AppConfig])

  val appLayer: RLayer[AppConfig, Tracing with ServerChannelFactory with EventLoopGroup] =
    (JaegerTracer.live >>> Tracing.live) ++ ServerChannelFactory.auto ++ EventLoopGroup.auto(0)

  val layer: URLayer[BackendApp, BackendServer] = ZLayer.fromFunction(BackendServer.apply _)

  override def run: Task[ExitCode] = {
    ZIO
      .serviceWithZIO[BackendServer](_.server.exitCode)
      .provide(
        configLayer,
        appLayer,
        layer
      )
  }
}
