package zio.telemetry.opentelemetry.example.http

import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.{ TextMapGetter, TextMapPropagator }
import zio.telemetry.opentelemetry.Tracing
import zio.telemetry.opentelemetry.TracingSyntax._
import zio.telemetry.opentelemetry.example.http.{ Status => ServiceStatus }
import zhttp.http._
import zio.json._
import zio.ZIO

import java.lang
import scala.jdk.CollectionConverters._

object StatusService {

  val propagator: TextMapPropagator  = W3CTraceContextPropagator.getInstance()
  val getter: TextMapGetter[List[Header]] = new TextMapGetter[List[Header]] {
    override def keys(carrier: List[Header]): lang.Iterable[String] =
      carrier.map(_.name.toString).asJava

    override def get(carrier: List[Header], key: String): String = {
      carrier.find(_.name.toString == key).map(_.value.toString).orNull
    }
  }

  val routes: HttpApp[Tracing, Throwable] =
    Http.collectM {
      case request@Method.GET -> Root / "status" =>
      val response = for {
        _ <- Tracing.addEvent("event from backend before response")
        response <- ZIO.succeed(Response.jsonString(ServiceStatus.up("backend").toJson))
        _ <- Tracing.addEvent("event from backend after response")
      } yield response

        response.spanFrom(propagator, request.headers, getter, "/status", SpanKind.SERVER)
    }

}
