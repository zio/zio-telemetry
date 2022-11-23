package zio.telemetry.opentelemetry.example.http

import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.api.trace.{ SpanKind, StatusCode }
import io.opentelemetry.context.propagation.TextMapPropagator
import zio._
import zhttp.http.{ !!, ->, /, Http, HttpApp, Method, Response }
import zio.json.EncoderOps
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.tracing.{ ErrorMapper, TextMapAdapter, Tracing }

import scala.collection.mutable

case class ProxyHttpApp(client: Client, tracing: Tracing, baggage: Baggage) {

  import tracing.aspects._

  private val tracePropagator: TextMapPropagator =
    W3CTraceContextPropagator.getInstance()

  private val baggagePropagator: TextMapPropagator =
    W3CBaggagePropagator.getInstance()

  private val errorMapper: ErrorMapper[Throwable] =
    ErrorMapper[Throwable] { case _ => StatusCode.UNSET }

  val routes: HttpApp[Any, Throwable] =
    Http.collectZIO { case Method.GET -> !! / "statuses" =>
      statuses @@ root("/statuses", SpanKind.SERVER, errorMapper)
    }

  def statuses: Task[Response] = {
    val carrier = mutable.Map.empty[String, String]

    for {
      _        <- tracing.setAttribute("http.method", "get")
      _        <- tracing.addEvent("proxy-event")
      _        <- baggage.set("proxy-baggage", "value from proxy")
      _        <- tracing.inject(tracePropagator, carrier, TextMapAdapter)
      _        <- baggage.inject(baggagePropagator, carrier, TextMapAdapter)
      statuses <- client.status(carrier.toMap)
    } yield Response.json(statuses.toJson)
  }

}

object ProxyHttpApp {

  val live: URLayer[Client with Tracing with Baggage, ProxyHttpApp] =
    ZLayer.fromFunction(ProxyHttpApp.apply _)

}
