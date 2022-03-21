package zio.telemetry.opentelemetry.example

import sttp.capabilities.WebSockets
import sttp.capabilities.zio.ZioStreams
import sttp.client3.SttpBackend
import zio.config.getConfig
import zio.config.typesafe.TypesafeConfig
import zio.config.magnolia.{ descriptor, Descriptor }
import zio.telemetry.opentelemetry.Tracing
import zio.telemetry.opentelemetry.example.config.AppConfig
import zio.telemetry.opentelemetry.example.http.{ Client, ProxyApp }
import zio.{ ExitCode, Task, URIO, ZEnv, ZIO, ZIOAppDefault, ZLayer }
import sttp.client3.asynchttpclient.zio.AsyncHttpClientZioBackend
import sttp.model.Uri
import zhttp.service.{ EventLoopGroup, Server, ServerChannelFactory }
import zhttp.service.server.ServerChannelFactory
import zio.Console.printLine

object ProxyServer extends ZIOAppDefault {
  implicit val sttpUriDescriptor: Descriptor[Uri] =
    Descriptor[String].transformOrFailLeft(Uri.parse)(_.toString)

  val server =
    getConfig[AppConfig].flatMap { conf =>
      val port = conf.proxy.host.port.getOrElse(8080)
      (Server.port(port) ++ Server.app(ProxyApp.routes)).make.use(_ =>
        printLine(s"ProxyServer started on port $port") *> ZIO.never
      )
    }

  val configLayer = TypesafeConfig.fromResourcePath(descriptor[AppConfig])

  val httpBackend: ZLayer[Any, Throwable, SttpBackend[Task, ZioStreams with WebSockets]] =
    ZLayer.fromAcquireRelease(AsyncHttpClientZioBackend())(_.close().ignore)

  val sttp: ZLayer[AppConfig, Throwable, Client.Service] = httpBackend >>> Client.live

  val appEnv: ZLayer[
    ZEnv with AppConfig,
    Throwable,
    Client.Service with Tracing.Service with ServerChannelFactory with EventLoopGroup
  ] =
    (JaegerTracer.live >>> Tracing.live) ++ sttp ++ ServerChannelFactory.auto ++ EventLoopGroup.auto(0)

  override def run: URIO[ZEnv, ExitCode] =
    server.provideSomeLayer(configLayer >+> appEnv).exitCode
}
