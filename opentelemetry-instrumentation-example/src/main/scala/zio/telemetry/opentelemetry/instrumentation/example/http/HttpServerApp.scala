package zio.telemetry.opentelemetry.instrumentation.example.http

import zhttp.http._
import zio.ZIO

object HttpServerApp {

  val routes: HttpApp[Any, Throwable] =
    Http.collectZIO { case _ @Method.GET -> _ / "health" =>
      ZIO.succeed(Response.ok)
    }

}
