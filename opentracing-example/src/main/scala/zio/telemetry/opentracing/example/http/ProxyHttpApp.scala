package zio.telemetry.opentracing.example.http

import io.opentracing.propagation.Format.Builtin.{ HTTP_HEADERS => HttpHeadersFormat }
import io.opentracing.propagation.TextMapAdapter
import io.opentracing.tag.Tags
import sttp.model.Method.GET
import zhttp.http.{ !!, ->, /, Http, HttpApp, Method, Response }
import zio.json.EncoderOps
import zio.telemetry.opentracing.OpenTracing
import zio._

import scala.collection.mutable
import scala.jdk.CollectionConverters._

case class ProxyHttpApp(client: Client, tracing: OpenTracing) {

  def routes: HttpApp[Any, Throwable] =
    Http.collectZIO { case Method.GET -> !! / "statuses" =>
      tracing
        .root("/statuses")(
          for {
            _        <- tracing.tag(Tags.SPAN_KIND.getKey, Tags.SPAN_KIND_CLIENT)(ZIO.unit)
            _        <- tracing.tag(Tags.HTTP_METHOD.getKey, GET.method)(ZIO.unit)
            _        <- tracing.setBaggageItem("proxy-baggage-item-key", "proxy-baggage-item-value")(ZIO.unit)
            carrier   = new TextMapAdapter(mutable.Map.empty[String, String].asJava)
            _        <- tracing.inject(HttpHeadersFormat, carrier)
            headers  <- extractHeaders(carrier)
            statuses <- client.status(headers)
          } yield Response.json(statuses.toJson)
        )
    }

  private def extractHeaders(adapter: TextMapAdapter): UIO[Map[String, String]] = {
    val m = mutable.Map.empty[String, String]

    ZIO.succeed {
      adapter.forEach { entry =>
        m.put(entry.getKey, entry.getValue)
        ()
      }
    }.as(m.toMap)
  }

}

object ProxyHttpApp {

  val live: URLayer[Client with OpenTracing, ProxyHttpApp] =
    ZLayer.fromFunction(ProxyHttpApp.apply _)

}
