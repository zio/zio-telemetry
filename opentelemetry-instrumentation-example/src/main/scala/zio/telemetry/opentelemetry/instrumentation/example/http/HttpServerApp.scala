package zio.telemetry.opentelemetry.instrumentation.example.http

import io.opentelemetry.api.trace.SpanKind
import zhttp.http._
import zio._
import zio.telemetry.opentelemetry.context.IncomingContextCarrier
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

case class HttpServerApp(tracing: Tracing) {

  import tracing.aspects._

  def headersCarrier(initial: Headers): IncomingContextCarrier[Headers] =
    new IncomingContextCarrier[Headers] {
      override val kernel: Headers = initial

      override def getAllKeys(carrier: Headers): Iterable[String] =
        carrier.headers.headersAsList.map(_._1)

      override def getByKey(carrier: Headers, key: String): Option[String] =
        carrier.headers.headerValue(key)

    }

  val routes: HttpApp[Any, Throwable] =
    Http.collectZIO { case request @ Method.GET -> _ / "health" =>
      val carrier = headersCarrier(request.headers)

      health @@ extractSpan(TraceContextPropagator.default, carrier, "/health", SpanKind.INTERNAL)
    }

  def health: UIO[Response] =
    for {
      _        <- tracing.addEvent("executing health logic")
      _        <- tracing.setAttribute("server-span", "internal")
      response <- ZIO.succeed(Response.ok)
    } yield response

}

object HttpServerApp {

  val live: URLayer[Tracing, HttpServerApp] =
    ZLayer.fromFunction(HttpServerApp.apply _)

}
