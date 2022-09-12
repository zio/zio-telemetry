package zio.telemetry.opentelemetry.example

import sttp.capabilities.WebSockets
import sttp.capabilities.zio.ZioStreams
import sttp.client3.SttpBackend
import sttp.client3.asynchttpclient.zio.AsyncHttpClientZioBackend
import sttp.model.Uri
import zhttp.service.server.ServerChannelFactory
import zhttp.service.{EventLoopGroup, Server, ServerChannelFactory}
import zio.Console.printLine
import zio.config.{ReadError, getConfig}
import zio.config.magnolia._
import zio.config.typesafe.TypesafeConfig
import zio.telemetry.opentelemetry.Tracing
import zio.telemetry.opentelemetry.example.config.AppConfig
import zio.telemetry.opentelemetry.example.http.{Client, ProxyApp}
import zio.{ExitCode, Layer, Task, ZIO, ZIOAppDefault, ZLayer, RIO, RLayer, URLayer, TaskLayer}
import ProxyServer.AppEnv

final case class ProxyServer(proxyApp: ProxyApp) {

  val server: RIO[AppEnv, Unit] =
    ZIO.scoped[AppEnv] {
      for {
        conf  <- getConfig[AppConfig]
        port   = conf.proxy.host.port.getOrElse(8080)
        server = Server.port(port) ++ Server.app(proxyApp.routes)
        _     <- server.make
        _     <- printLine(s"ProxyServer started on port $port") *> ZIO.never
      } yield ()
    }
}

object ProxyServer extends ZIOAppDefault {

  implicit val sttpUriDescriptor: Descriptor[Uri] =
    Descriptor[String].transformOrFailLeft(Uri.parse)(_.toString)

  type AppEnv = AppConfig with Client with Tracing with ServerChannelFactory with EventLoopGroup

  val configLayer: Layer[ReadError[String], AppConfig] = TypesafeConfig.fromResourcePath(descriptor[AppConfig])

  val httpBackend: TaskLayer[SttpBackend[Task, ZioStreams with WebSockets]] =
    ZLayer.scoped {
      ZIO.acquireRelease(AsyncHttpClientZioBackend())(_.close().ignore)
    }

  val sttp: RLayer[AppConfig, Client] = httpBackend >>> Client.live

  val appLayer: RLayer[AppConfig, Tracing with Client with ServerChannelFactory with EventLoopGroup] =
    (JaegerTracer.live >>> Tracing.live) ++ sttp ++ ServerChannelFactory.auto ++ EventLoopGroup.auto(0)

  val layer: URLayer[ProxyApp, ProxyServer] = ZLayer.fromFunction(ProxyServer.apply _)

  override def run: Task[ExitCode] = {
    ZIO
      .serviceWithZIO[ProxyServer](_.server.exitCode)
      .provide(
        configLayer,
        appLayer,
        layer
      )
  }
}
