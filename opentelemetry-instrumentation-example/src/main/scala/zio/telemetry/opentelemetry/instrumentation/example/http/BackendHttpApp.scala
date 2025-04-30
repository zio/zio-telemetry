package zio.telemetry.opentelemetry.instrumentation.example.http

import zio._
import zio.http._
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing

case class BackendHttpApp(openTelemetry: OpenTelemetry, tracing: Tracing) {

  val routes =
    Routes(
      Method.GET / "example-endpoint" ->
        handler {
          exampleEndpoint @@
            tracing.aspects.span("example-endpoint") @@
            openTelemetry.aspects.autoinstrumented
        }
    )

  private def exampleEndpoint: UIO[Response] =
    for {
      _ <- tracing.addEvent("executing endpoint logic")
      _ <- tracing.setAttribute("zio", "telemetry")
      _ <- ZIO.logInfo("example endpoint processing finished on the server")
    } yield Response.text("welcome")

}

object BackendHttpApp {

  val live: URLayer[OpenTelemetry with Tracing, BackendHttpApp] =
    ZLayer.fromFunction(BackendHttpApp.apply _)

}
