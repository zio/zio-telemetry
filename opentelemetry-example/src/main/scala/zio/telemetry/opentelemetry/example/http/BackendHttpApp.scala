package zio.telemetry.opentelemetry.example.http

import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.{ TextMapGetter, TextMapPropagator }
import zio.telemetry.opentelemetry.example.http.{ Status => ServiceStatus }
import zhttp.http.{ !!, ->, /, Headers, Http, HttpApp, Method, Response }
import zio.json.EncoderOps
import zio._
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.tracing.Tracing

import java.lang
import scala.jdk.CollectionConverters._

case class BackendHttpApp(tracing: Tracing, baggage: Baggage) {

  import tracing.aspects._

  val propagator: TextMapPropagator  = W3CTraceContextPropagator.getInstance()
  val getter: TextMapGetter[Headers] = new TextMapGetter[Headers] {
    override def keys(carrier: Headers): lang.Iterable[String] =
      carrier.headers.headersAsList.map(_._1).asJava

    override def get(carrier: Headers, key: String): String =
      carrier.headers.headerValue(key).orNull
  }

  val routes: HttpApp[Any, Throwable] =
    Http.collectZIO { case request @ Method.GET -> !! / "status" =>
      status @@ spanFrom(propagator, request.headers, getter, "/status", SpanKind.SERVER)
    }

  def status: UIO[Response] =
    for {
      proxyBaggage <- baggage.get("proxy-baggage")
      _            <- tracing.setAttribute("proxy-baggage", proxyBaggage.getOrElse("NO BAGGAGE"))
      _            <- tracing.addEvent("event from backend before response")
      response     <- ZIO.succeed(Response.json(ServiceStatus.up("backend").toJson))
      _            <- tracing.addEvent("event from backend after response")
    } yield response

}

object BackendHttpApp {

  val live: URLayer[Tracing with Baggage, BackendHttpApp] =
    ZLayer.fromFunction(BackendHttpApp.apply _)

}
