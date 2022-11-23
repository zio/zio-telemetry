package zio.telemetry.opentelemetry.example.http

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.api.trace.{ SpanKind, StatusCode }
import io.opentelemetry.context.propagation.{ TextMapPropagator, TextMapSetter }
import zio._
import zhttp.http.{ !!, ->, /, Http, HttpApp, Method, Response }
import zio.json.EncoderOps
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.tracing.{ ErrorMapper, Tracing }

import scala.collection.mutable

case class ProxyHttpApp(client: Client, tracing: Tracing, baggage: Baggage) {

  import tracing.aspects._

  private val propagator: TextMapPropagator =
    W3CTraceContextPropagator.getInstance()

  private val setter: TextMapSetter[mutable.Map[String, String]] =
    (carrier, key, value) => carrier.update(key, value)

  private val errorMapper: ErrorMapper[Throwable] =
    ErrorMapper[Throwable] { case _ => StatusCode.UNSET }

  val routes: HttpApp[Any, Throwable] =
    Http.collectZIO { case Method.GET -> !! / "statuses" =>
      (for {
        _        <- tracing.setAttribute("http.method", "get")
        _        <- tracing.addEvent("proxy-event")
        _        <- baggage.set("proxy-baggage", "proxy-baggage-value")
        carrier   = mutable.Map.empty[String, String]
        _        <- tracing.inject(propagator, carrier, setter)
        statuses <- client.status(carrier.toMap)
      } yield Response.json(statuses.toJson)) @@ root("/statuses", SpanKind.SERVER, errorMapper)
    }

}

object ProxyHttpApp {

  val live: URLayer[Client with Tracing with Baggage, ProxyHttpApp] =
    ZLayer.fromFunction(ProxyHttpApp.apply _)

}
