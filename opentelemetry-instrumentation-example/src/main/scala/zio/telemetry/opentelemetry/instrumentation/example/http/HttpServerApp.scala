package zio.telemetry.opentelemetry.instrumentation.example.http

import zio.http._
import zio._
import zio.telemetry.opentelemetry.tracing.Tracing

case class HttpServerApp(tracing: Tracing) {

  import tracing.aspects._

  val routes: HttpApp[Any, Nothing] =
    Http.collectZIO { case _ @Method.GET -> _ / "health" =>
      health @@ span("health-endpoint")
    }

  def health: UIO[Response] =
    for {
      _        <- tracing.addEvent("executing health logic")
      _        <- tracing.setAttribute("zio", "telemetry")
      _        <- ZIO.logInfo("health processing finished on the server")
      response <- ZIO.succeed(Response.ok)
    } yield response

}

object HttpServerApp {

  val live: URLayer[Tracing, HttpServerApp] =
    ZLayer.fromFunction(HttpServerApp.apply _)

}
