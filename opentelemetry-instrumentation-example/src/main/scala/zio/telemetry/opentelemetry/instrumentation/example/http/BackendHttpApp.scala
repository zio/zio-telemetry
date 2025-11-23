package zio.telemetry.opentelemetry.instrumentation.example.http

import zio._
import zio.http._
import zio.telemetry.opentelemetry.core.OpenTelemetry
import zio.telemetry.opentelemetry.core.trace.Tracer

case class BackendHttpApp(openTelemetry: OpenTelemetry, tracer: Tracer) {

  val routes: Routes[Any, Nothing] =
    Routes(
      Method.GET / "example-endpoint" ->
        handler {
          tracer.span("example-endpoint") { span =>
            for {
              _ <- span.addEvent("executing endpoint logic")
              _ <- span.setAttribute("zio", "telemetry")
              _ <- ZIO.logInfo("example endpoint processing finished on the server")
            } yield Response.text("welcome")
          } @@ openTelemetry.aspects.autoinstrumented
        }
    )

}

object BackendHttpApp {

  val live: URLayer[OpenTelemetry with Tracer, BackendHttpApp] =
    ZLayer.fromFunction(BackendHttpApp.apply _)

}
