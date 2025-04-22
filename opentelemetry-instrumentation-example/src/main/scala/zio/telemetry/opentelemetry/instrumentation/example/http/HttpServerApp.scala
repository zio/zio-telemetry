package zio.telemetry.opentelemetry.instrumentation.example.http

import zio._
import zio.http._
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing

case class HttpServerApp(openTelemetry: OpenTelemetry, tracing: Tracing) {

  import tracing.aspects._

  val routes =
    Routes(
      Method.GET / "health" ->
        handler {
          openTelemetry.autoinstrumented(
            health @@ span("health-endpoint")
          )
        }
    )

  def health: UIO[Response] =
    for {
      _ <- tracing.addEvent("executing health logic")
      _ <- tracing.setAttribute("zio", "telemetry")
      _ <- ZIO.logInfo("health processing finished on the server")
    } yield Response.ok

}

object HttpServerApp {

  val live: URLayer[OpenTelemetry with Tracing, HttpServerApp] =
    ZLayer.fromFunction(HttpServerApp.apply _)

}
