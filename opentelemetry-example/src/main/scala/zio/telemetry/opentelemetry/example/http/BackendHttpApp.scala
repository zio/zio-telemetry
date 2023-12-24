package zio.telemetry.opentelemetry.example.http

import io.opentelemetry.api.trace.SpanKind
import zio.http._
import zio._
import zio.json.EncoderOps
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.baggage.propagation.BaggagePropagator
import zio.telemetry.opentelemetry.context.IncomingContextCarrier
import zio.telemetry.opentelemetry.example.http.{Status => ServiceStatus}
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator
import zio.telemetry.opentelemetry.metrics.Meter

case class BackendHttpApp(tracing: Tracing, meter: Meter, baggage: Baggage) {

  import tracing.aspects._

  def headersCarrier(initial: Headers): IncomingContextCarrier[Headers] =
    new IncomingContextCarrier[Headers] {
      override val kernel: Headers = initial

      override def getAllKeys(carrier: Headers): Iterable[String] =
        carrier.headers.map(_.headerName)

      override def getByKey(carrier: Headers, key: String): Option[String] =
        carrier.headers.get(key)

    }

  val routes: HttpApp[Any, Nothing] =
    Http.collectZIO { case request @ Method.GET -> _ / "status" =>
      val carrier = headersCarrier(request.headers)

      (baggage.extract(BaggagePropagator.default, carrier) *> status) @@
        extractSpan(TraceContextPropagator.default, carrier, "/status", SpanKind.SERVER)
    }

  def status: UIO[Response] =
    for {
      proxyBaggage <- baggage.get("proxy-baggage")
      _            <- tracing.setAttribute("proxy-baggage", proxyBaggage.getOrElse("NO BAGGAGE"))
      _            <- tracing.addEvent("event from backend before response")
      response     <- ZIO.succeed(Response.json(ServiceStatus.up("backend").toJson))
      _            <- tracing.addEvent("event from backend after response")
      _            <- ZIO.logInfo("status processing finished on backend")
      _            <- meter.counter("status_requests_count").flatMap(_.inc).orDie
    } yield response

}

object BackendHttpApp {

  val live: URLayer[Tracing with Meter with Baggage, BackendHttpApp] =
    ZLayer.fromFunction(BackendHttpApp.apply _)

}
