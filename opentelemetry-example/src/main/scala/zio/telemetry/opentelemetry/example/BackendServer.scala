package zio.telemetry.opentelemetry.example

import zio.clock.Clock
import zio.console.putStrLn
import zio.config.getConfig
import zio.config.typesafe.TypesafeConfig
import zio.config.magnolia.{ descriptor, Descriptor }
import zio.telemetry.opentelemetry.Tracing
import zio.telemetry.opentelemetry.example.config.AppConfig
import zio.telemetry.opentelemetry.example.http.{ Client, StatusService }
import zio.{ App, Managed, ZIO, ZLayer }
import sttp.client3.asynchttpclient.zio.AsyncHttpClientZioBackend
import sttp.model.Uri
import zhttp.service.{ EventLoopGroup, Server }
import zhttp.service.server.ServerChannelFactory

object BackendServer extends App {
  implicit val sttpUriDescriptor: Descriptor[Uri] =
    Descriptor[String].transformOrFailLeft(Uri.parse)(_.toString)

  val server =
    getConfig[AppConfig].flatMap { conf =>
      val port = conf.backend.host.port.getOrElse(9000)
      (Server.port(port) ++ Server.app(StatusService.routes)).make.use(_ =>
        putStrLn(s"BackendServer started on port $port") *> ZIO.never
      )
    }

  val configLayer = TypesafeConfig.fromDefaultLoader(descriptor[AppConfig])
  val httpBackend = ZLayer.fromManaged(Managed.make(AsyncHttpClientZioBackend())(_.close().ignore))
  val client      = configLayer ++ httpBackend >>> Client.live
  val tracer      = configLayer >>> JaegerTracer.live
  val envLayer    = tracer ++ Clock.live >>> Tracing.live ++ configLayer ++ client

  override def run(args: List[String]) =
    server
      .provideCustomLayer(
        envLayer
          ++ ServerChannelFactory.auto
          ++ EventLoopGroup.auto(0)
      )
      .exitCode
}
