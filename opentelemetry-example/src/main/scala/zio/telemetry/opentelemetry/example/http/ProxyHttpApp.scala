package zio.telemetry.opentelemetry.example.http

import io.opentelemetry.api.trace.{SpanKind, StatusCode}
import zhttp.http._
import zio._
import zio.json.EncoderOps
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.baggage.propagation.BaggagePropagator
import zio.telemetry.opentelemetry.context.OutgoingContextCarrier
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator
import zio.telemetry.opentelemetry.tracing.{ErrorMapper, Tracing}

case class ProxyHttpApp(client: Client, tracing: Tracing, baggage: Baggage) {

  import tracing.aspects._

  private val errorMapper: ErrorMapper[Throwable] =
    ErrorMapper[Throwable] { case _ => StatusCode.UNSET }

  val routes: HttpApp[Any, Throwable] =
    Http.collectZIO { case Method.GET -> _ / "statuses" =>
      statuses @@ root("/statuses", SpanKind.SERVER, errorMapper = errorMapper)
    }

  def statuses: Task[Response] = {
    val carrier = OutgoingContextCarrier.default()

    for {
      _        <- tracing.setAttribute("http.method", "get")
      _        <- tracing.addEvent("proxy-event")
      _        <- baggage.set("proxy-baggage", "value from proxy")
      _        <- tracing.inject(TraceContextPropagator.default, carrier)
      _        <- baggage.inject(BaggagePropagator.default, carrier)
      statuses <- client.status(carrier.kernel.toMap)
    } yield Response.json(statuses.toJson)
  }

}

object ProxyHttpApp {

  val live: URLayer[Client with Tracing with Baggage, ProxyHttpApp] =
    ZLayer.fromFunction(ProxyHttpApp.apply _)

}
