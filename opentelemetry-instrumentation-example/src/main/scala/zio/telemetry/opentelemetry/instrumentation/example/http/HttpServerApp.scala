package zio.telemetry.opentelemetry.instrumentation.example.http

import zhttp.http._
import zio._
import zio.telemetry.opentelemetry.tracing.Tracing

case class HttpServerApp(tracing: Tracing) {

  import tracing.aspects._

  val routes: HttpApp[Any, Throwable] =
    Http.collectZIO { case _ @Method.GET -> _ / "health" =>
      for {
        _        <- serverSpan
        _        <- healthSpan @@ span("health span")
        response <- ZIO.succeed(Response.ok)
      } yield response

    }

  private def serverSpan: UIO[Unit] =
    for {
      _ <- tracing.setAttribute("zio", true)
      _ <- tracing.addEvent("running server span")
    } yield ()

  private def healthSpan: UIO[Unit] =
    for {
      _ <- tracing.addEvent("executing health logic")
      _ <- tracing.setAttribute("zio", "telemetry")
    } yield ()

}

object HttpServerApp {

  val live: URLayer[Tracing, HttpServerApp] =
    ZLayer.fromFunction(HttpServerApp.apply _)

}
