package zio.telemetry.opentelemetry.example.http

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.api.trace.{ SpanKind, StatusCode }
import io.opentelemetry.context.propagation.{ TextMapPropagator, TextMapSetter }
import zio.{ URLayer, ZIO, ZLayer }
import zio.telemetry.opentelemetry.Tracing
import zhttp.http.{ !!, ->, /, Http, HttpApp, Method, Response }
import zio.json.EncoderOps
import ProxyApp._

import scala.collection.mutable

final case class ProxyApp(tracing: Tracing) {

  val routes: HttpApp[Client, Throwable] = Http.collectZIO { case Method.GET -> !! / "statuses" =>
    tracing.root("/statuses", SpanKind.SERVER, errorMapper) {
      for {
        carrier <- ZIO.succeed(mutable.Map[String, String]().empty)
        _       <- tracing.setAttribute("http.method", "get")
        _       <- tracing.addEvent("proxy-event")
        _       <- tracing.inject(propagator, carrier, setter)
        res     <- Client.status(carrier.toMap).map(s => Response.json(s.toJson))
      } yield res
    }
  }
}

object ProxyApp {

  val propagator: TextMapPropagator = W3CTraceContextPropagator.getInstance()

  val setter: TextMapSetter[mutable.Map[String, String]] = (carrier, key, value) => carrier.update(key, value)

  val errorMapper: PartialFunction[Throwable, StatusCode] = { case _ => StatusCode.UNSET }

  val layer: URLayer[Tracing, ProxyApp] = ZLayer.fromFunction(ProxyApp.apply _)
}
