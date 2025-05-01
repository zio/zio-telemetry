package zio.telemetry.opentelemetry.instrumentation.example.http

import zio._
import zio.http._
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.trace.Tracer

case class BackendHttpApp(openTelemetry: OpenTelemetry, tracer: Tracer) {

  val routes =
    Routes(
      Method.GET / "example-endpoint" ->
        handler {
          exampleEndpoint @@
            tracer.aspects.span("example-endpoint") @@
            openTelemetry.aspects.autoinstrumented
        }
    )

  private def exampleEndpoint: UIO[Response] =
    for {
      _ <- tracer.addEvent("executing endpoint logic")
      _ <- tracer.setAttribute("zio", "telemetry")
      _ <- ZIO.logInfo("example endpoint processing finished on the server")
    } yield Response.text("welcome")

}

object BackendHttpApp {

  val live: URLayer[OpenTelemetry with Tracer, BackendHttpApp] =
    ZLayer.fromFunction(BackendHttpApp.apply _)

}
