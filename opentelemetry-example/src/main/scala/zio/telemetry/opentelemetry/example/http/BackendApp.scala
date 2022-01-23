package zio.telemetry.opentelemetry.example.http

import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.{TextMapGetter, TextMapPropagator}
import zio.telemetry.opentelemetry.Tracing
import zio.telemetry.opentelemetry.TracingSyntax._
import zio.telemetry.opentelemetry.example.http.{Status => ServiceStatus}
import zhttp.http.{!!, ->, /, Headers, Http, HttpApp, Method, Response}
import zio.json.EncoderOps
import zio.ZIO

import java.lang
import scala.jdk.CollectionConverters._

object BackendApp {

  val propagator: TextMapPropagator       = W3CTraceContextPropagator.getInstance()
  val getter: TextMapGetter[Headers] = new TextMapGetter[Headers] {
    override def keys(carrier: Headers): lang.Iterable[String] =
      carrier.toChunk.map(_._1.toString).asJava

    override def get(carrier: Headers, key: String): String =
      carrier.getHeader(key).map(_._2.toString).orNull
  }

  val routes: HttpApp[Tracing, Throwable] =
    Http.collectZIO { case request @ Method.GET -> !! / "status" =>
      val response = for {
        _        <- Tracing.addEvent("event from backend before response")
        response <- ZIO.succeed(Response.json(ServiceStatus.up("backend").toJson))
        _        <- Tracing.addEvent("event from backend after response")
      } yield response

      response.spanFrom(propagator, request.getHeaders, getter, "/status", SpanKind.SERVER)
    }

}
