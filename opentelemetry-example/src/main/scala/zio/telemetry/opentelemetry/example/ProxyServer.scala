package zio.telemetry.opentelemetry.example

import org.http4s.blaze.server.BlazeServerBuilder
import org.http4s.server.{ defaults, Router }
import zio.clock.Clock
import zio.interop.catz._
import zio.telemetry.opentelemetry.Tracing
import zio.telemetry.opentelemetry.example.config.{ Config, Configuration }
import zio.telemetry.opentelemetry.example.http.{ AppEnv, AppTask, Client, StatusesService }
import zio.{ ExitCode, Managed, ZIO, ZLayer }
import org.http4s.syntax.kleisli._
import sttp.client.asynchttpclient.zio.AsyncHttpClientZioBackend

object ProxyServer extends zio.App {

  val router = Router[AppTask]("/" -> StatusesService.routes).orNotFound

  val server =
    ZIO
      .runtime[AppEnv]
      .flatMap { implicit runtime =>
        implicit val ec = runtime.platform.executor.asEC
        BlazeServerBuilder[AppTask](ec)
          .bindHttp(
            runtime.environment.get[Config].proxy.host.port.getOrElse(defaults.HttpPort),
            runtime.environment.get[Config].proxy.host.host
          )
          .withHttpApp(router)
          .serve
          .compile
          .drain
      }

  val httpBackend = ZLayer.fromManaged(Managed.make(AsyncHttpClientZioBackend())(_.close().ignore))
  val client      = Configuration.live ++ httpBackend >>> Client.live
  val tracer      = Configuration.live >>> JaegerTracer.live
  val envLayer    = tracer ++ Clock.live >>> Tracing.live ++ Configuration.live ++ client

  override def run(args: List[String]) =
    server.provideCustomLayer(envLayer).fold(_ => ExitCode.failure, _ => ExitCode.success)
}
