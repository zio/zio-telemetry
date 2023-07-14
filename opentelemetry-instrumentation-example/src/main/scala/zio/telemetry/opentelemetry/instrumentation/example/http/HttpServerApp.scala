package zio.telemetry.opentelemetry.instrumentation.example.http

import zhttp.http._
import zio._
import zio.telemetry.opentelemetry.tracing.Tracing

case class HttpServerApp(tracing: Tracing) {

  import tracing.aspects._

  val routes: HttpApp[Any, Throwable] =
    Http.collectZIO { case _ @Method.GET -> _ / "health" =>
      health @@ span[Throwable, Response]("health-endpoint")
    }

  def health: UIO[Response] =
    for {
      _        <- tracing.addEvent("executing health logic")
      _        <- tracing.setAttribute("zio", "telemetry")
      response <- ZIO.succeed(Response.ok)
    } yield response

}

object HttpServerApp {

  val live: URLayer[Tracing, HttpServerApp] =
    ZLayer.fromFunction(HttpServerApp.apply _)

}
