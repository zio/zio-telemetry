package zio.telemetry.opentelemetry.example

import org.http4s.server.{ Router, _ }
import org.http4s.server.blaze.BlazeServerBuilder
import zio.clock.Clock
import zio.interop.catz._
import zio.telemetry.opentelemetry.Tracing
import zio.telemetry.opentelemetry.example.config.{ Config, Configuration }
import zio.telemetry.opentelemetry.example.http.{ AppEnv, AppTask, Client, StatusService }
import zio.{ Managed, ZIO, ZLayer }
import org.http4s.syntax.kleisli._
import sttp.client.asynchttpclient.zio.AsyncHttpClientZioBackend

object BackendServer extends zio.App {
  val router = Router[AppTask]("/" -> StatusService.routes).orNotFound

  val server =
    ZIO
      .runtime[AppEnv]
      .flatMap(implicit runtime =>
        BlazeServerBuilder[AppTask]
          .bindHttp(
            runtime.environment.get[Config].backend.host.port.getOrElse(defaults.HttpPort),
            runtime.environment.get[Config].backend.host.host
          )
          .withHttpApp(router)
          .serve
          .compile
          .drain
      )

  val configuration = Configuration.live
  val httpBackend   = ZLayer.fromManaged(Managed.make(AsyncHttpClientZioBackend())(_.close.ignore))
  val client        = configuration ++ httpBackend >>> Client.live
  val tracer        = configuration >>> JaegerTracer.live("zio-backend")
  val envLayer      = tracer ++ Clock.live >>> Tracing.live ++ configuration ++ client

  override def run(args: List[String]) = server.provideCustomLayer(envLayer).fold(_ => 1, _ => 0)
}
