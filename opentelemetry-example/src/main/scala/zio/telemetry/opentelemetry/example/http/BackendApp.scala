package zio.telemetry.opentelemetry.example.http

import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.{ TextMapGetter, TextMapPropagator }
import zio.telemetry.opentelemetry.Tracing
import zio.telemetry.opentelemetry.TracingSyntax._
import zio.telemetry.opentelemetry.example.http.{ Status => ServiceStatus }
import zhttp.http.{ !!, ->, /, Headers, Http, HttpApp, Method, Response }
import zio.json.EncoderOps
import zio.ZIO
import BackendApp._

import java.lang
import scala.jdk.CollectionConverters._

final case class BackendApp(tracing: Tracing) {

  val routes: HttpApp[Tracing, Throwable] =
    Http.collectZIO { case request @ Method.GET -> !! / "status" =>
      val response = for {
        _        <- tracing.addEvent("event from backend before response")
        response <- ZIO.succeed(Response.json(ServiceStatus.up("backend").toJson))
        _        <- tracing.addEvent("event from backend after response")
      } yield response

      response.spanFrom(propagator, request.headers, getter, "/status", SpanKind.SERVER)
    }
}

object BackendApp {

  val propagator: TextMapPropagator  = W3CTraceContextPropagator.getInstance()

  val getter: TextMapGetter[Headers] = new TextMapGetter[Headers] {
    override def keys(carrier: Headers): lang.Iterable[String] =
      carrier.headers.headersAsList.map(_._1).asJava

    override def get(carrier: Headers, key: String): String =
      carrier.headers.headerValue(key).orNull
  }
}
