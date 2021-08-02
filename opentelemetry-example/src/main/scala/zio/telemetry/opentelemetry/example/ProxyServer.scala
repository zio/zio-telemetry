package zio.telemetry.opentelemetry.example

import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.server.{ defaults, Router }
import zio.clock.Clock
import zio.config.getConfig
import zio.config.typesafe.TypesafeConfig
import zio.config.magnolia.{ descriptor, Descriptor }
import zio.interop.catz._
import zio.telemetry.opentelemetry.Tracing
import zio.telemetry.opentelemetry.example.config.AppConfig
import zio.telemetry.opentelemetry.example.http.{ AppEnv, AppTask, Client, StatusesService }
import zio.{ ExitCode, Managed, ZIO, ZLayer, App }
import org.http4s.syntax.kleisli._
import sttp.client.asynchttpclient.zio.AsyncHttpClientZioBackend
import sttp.model.Uri

object ProxyServer extends App {
  implicit val sttpUriDescriptor: Descriptor[Uri] =
    Descriptor[String].transformOrFailLeft(Uri.parse)(_.toString)

  val router = Router[AppTask]("/" -> StatusesService.routes).orNotFound

  val server =
    ZIO
      .runtime[AppEnv]
      .flatMap { implicit runtime =>
        implicit val ec = runtime.platform.executor.asEC
        getConfig[AppConfig].flatMap { conf =>
          BlazeServerBuilder[AppTask](ec)
            .bindHttp(
              conf.proxy.host.port.getOrElse(defaults.HttpPort),
              conf.proxy.host.host.getOrElse(defaults.IPv4Host)
            )
            .withHttpApp(router)
            .serve
            .compile
            .drain
        }
      }

  val configLayer = TypesafeConfig.fromDefaultLoader(descriptor[AppConfig])
  val httpBackend = ZLayer.fromManaged(Managed.make(AsyncHttpClientZioBackend())(_.close().ignore))
  val client      = configLayer ++ httpBackend >>> Client.live
  val tracer      = configLayer >>> JaegerTracer.live
  val envLayer    = tracer ++ Clock.live >>> Tracing.live ++ configLayer ++ client

  override def run(args: List[String]) =
    server.provideCustomLayer(envLayer).fold(_ => ExitCode.failure, _ => ExitCode.success)
}
