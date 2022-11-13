package zio.telemetry.opentelemetry.example.http

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.api.trace.{ SpanKind, StatusCode }
import io.opentelemetry.context.propagation.{ TextMapPropagator, TextMapSetter }
import zio._
import zio.telemetry.opentelemetry.{ ErrorMapper, Tracing }
import zhttp.http.{ !!, ->, /, Http, HttpApp, Method, Response }
import zio.json.EncoderOps

import scala.collection.mutable

case class ProxyHttpApp(client: Client, tracing: Tracing) {

  private val propagator: TextMapPropagator =
    W3CTraceContextPropagator.getInstance()

  private val setter: TextMapSetter[mutable.Map[String, String]] =
    (carrier, key, value) => carrier.update(key, value)

  private val errorMapper: ErrorMapper[Throwable] = ErrorMapper[Throwable] { case _ => StatusCode.UNSET }

  val routes: HttpApp[Any, Throwable] =
    Http.collectZIO { case Method.GET -> !! / "statuses" =>
      tracing.root("/statuses", SpanKind.SERVER, errorMapper) {
        for {
          _        <- tracing.setAttribute("http.method", "get")
          _        <- tracing.addEvent("proxy-event")
          carrier   = mutable.Map.empty[String, String]
          _        <- tracing.inject(propagator, carrier, setter)
          statuses <- client.status(carrier.toMap)
        } yield Response.json(statuses.toJson)
      }
    }

}

object ProxyHttpApp {

  val live: URLayer[Client with Tracing, ProxyHttpApp] =
    ZLayer.fromFunction(ProxyHttpApp.apply _)

}
