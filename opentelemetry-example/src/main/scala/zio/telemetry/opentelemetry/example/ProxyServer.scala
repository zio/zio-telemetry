package zio.telemetry.opentelemetry.example

import sttp.capabilities.WebSockets
import sttp.capabilities.zio.ZioStreams
import sttp.client3.SttpBackend
import sttp.client3.asynchttpclient.zio.AsyncHttpClientZioBackend
import sttp.model.Uri
import zhttp.service.server.ServerChannelFactory
import zhttp.service.{ EventLoopGroup, Server, ServerChannelFactory }
import zio.Console.printLine
import zio.config.getConfig
import zio.config.magnolia._
import zio.config.typesafe.TypesafeConfig
import zio.telemetry.opentelemetry.Tracing
import zio.telemetry.opentelemetry.example.config.AppConfig
import zio.telemetry.opentelemetry.example.http.{ Client, ProxyApp }
import zio.{ Task, ZIO, ZIOAppDefault, ZLayer }

object ProxyServer extends ZIOAppDefault {
  implicit val sttpUriDescriptor: Descriptor[Uri] =
    Descriptor[String].transformOrFailLeft(Uri.parse)(_.toString)

  type AppEnv = AppConfig with Client with Tracing with ServerChannelFactory with EventLoopGroup

  val server =
    ZIO.scoped[AppEnv] {
      for {
        conf  <- getConfig[AppConfig]
        port   = conf.proxy.host.port.getOrElse(8080)
        server = Server.port(port) ++ Server.app(ProxyApp.routes)
        _     <- server.make
        _     <- printLine(s"ProxyServer started on port $port") *> ZIO.never
      } yield ()
    }

  val configLayer = TypesafeConfig.fromResourcePath(descriptor[AppConfig])

  val httpBackend: ZLayer[Any, Throwable, SttpBackend[Task, ZioStreams with WebSockets]] =
    ZLayer.scoped {
      ZIO.acquireRelease(AsyncHttpClientZioBackend())(_.close().ignore)
    }

  val sttp: ZLayer[AppConfig, Throwable, Client] = httpBackend >>> Client.live

  val appEnv =
    (JaegerTracer.live >>> Tracing.live) ++ sttp ++ ServerChannelFactory.auto ++ EventLoopGroup.auto(0)

  override def run =
    ZIO.provideLayer(configLayer >+> appEnv)(server.exitCode)
}
