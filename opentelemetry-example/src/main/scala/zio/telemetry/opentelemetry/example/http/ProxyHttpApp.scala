package zio.telemetry.opentelemetry.example.http

import io.opentelemetry.api.trace.{SpanKind, StatusCode}
import zio.http._
import zio._
import zio.json.EncoderOps
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.baggage.propagation.BaggagePropagator
import zio.telemetry.opentelemetry.context.OutgoingContextCarrier
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator
import zio.telemetry.opentelemetry.tracing.{StatusMapper, Tracing}

case class ProxyHttpApp(client: BackendClient, tracing: Tracing, baggage: Baggage) {

  import tracing.aspects._

  private val statusMapper: StatusMapper[Throwable, Any] = StatusMapper.failureThrowable(_ => StatusCode.UNSET)

  val routes: HttpApp[Any, Nothing] =
    Http.collectZIO { case Method.GET -> _ / "statuses" =>
      statuses @@ root("/statuses", SpanKind.SERVER, statusMapper = statusMapper)
    }

  def statuses: UIO[Response] = {
    val carrier = OutgoingContextCarrier.default()

    for {
      _        <- tracing.setAttribute("http.method", "get")
      _        <- tracing.addEvent("proxy-event")
      _        <- baggage.set("proxy-baggage", "value from proxy")
      _        <- tracing.inject(TraceContextPropagator.default, carrier)
      _        <- baggage.inject(BaggagePropagator.default, carrier)
      statuses <- client.status(carrier.kernel.toMap).catchAll(_ => ZIO.succeed(Statuses(List.empty)))
      _        <- ZIO.logInfo("statuses processing finished on proxy")
    } yield Response.json(statuses.toJson)
  }

}

object ProxyHttpApp {

  val live: URLayer[BackendClient with Tracing with Baggage, ProxyHttpApp] =
    ZLayer.fromFunction(ProxyHttpApp.apply _)

}
