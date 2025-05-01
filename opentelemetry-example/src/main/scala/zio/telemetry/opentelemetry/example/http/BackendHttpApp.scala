package zio.telemetry.opentelemetry.example.http

import io.opentelemetry.api.trace.SpanKind
import zio._
import zio.http._
import zio.json.EncoderOps
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.context.IncomingContextCarrier
import zio.telemetry.opentelemetry.example.http.{BackendStatus => ServiceStatus}
import zio.telemetry.opentelemetry.metrics.{Counter, Meter}
import zio.telemetry.opentelemetry.trace.Tracer

case class BackendHttpApp(openTelemetry: OpenTelemetry, tracer: Tracer, statusRequestsCounter: Counter[Long]) {

  def headersCarrier(initial: Headers): IncomingContextCarrier[Headers] =
    new IncomingContextCarrier[Headers] {
      override val kernel: Headers = initial

      override def getAllKeys(carrier: Headers): Iterable[String] =
        carrier.headers.map(_.headerName)

      override def getByKey(carrier: Headers, key: String): Option[String] =
        carrier.headers.get(key)

    }

  val routes =
    Routes(
      Method.GET / "status" ->
        handler { request: Request =>
          val carrier = headersCarrier(request.headers)

          status @@
            tracer.aspects.span("/status", SpanKind.SERVER) @@
            openTelemetry.aspects.continue(carrier)
        }
    )

  def status: UIO[Response] =
    for {
      proxyBaggage <- openTelemetry.baggage.get("proxy-baggage")
      _            <- tracer.setAttribute("proxy-baggage", proxyBaggage.getOrElse("NO BAGGAGE"))
      _            <- tracer.addEvent("event from backend before response")
      response     <- ZIO.succeed(Response.json(ServiceStatus.up("backend").toJson))
      _            <- tracer.addEvent("event from backend after response")
      _            <- ZIO.logInfo("status processing finished on backend")
      _            <- statusRequestsCounter.inc()
    } yield response

}

object BackendHttpApp {

  val live: URLayer[OpenTelemetry with Tracer with Meter, BackendHttpApp] = {
    val counterLayer = ZLayer(ZIO.serviceWithZIO[Meter](_.counter("status_requests_count")))

    counterLayer >>> ZLayer.fromFunction(BackendHttpApp.apply _)
  }

}
