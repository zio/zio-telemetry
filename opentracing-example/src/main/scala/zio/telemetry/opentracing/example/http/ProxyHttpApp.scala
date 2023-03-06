package zio.telemetry.opentracing.example.http

import io.opentracing.propagation.Format.Builtin.{ HTTP_HEADERS => HttpHeadersFormat }
import io.opentracing.propagation.TextMapAdapter
import io.opentracing.tag.Tags
import sttp.model.Method.GET
import zhttp.http._
import zio._
import zio.json.EncoderOps
import zio.telemetry.opentracing.OpenTracing

import scala.collection.mutable
import scala.jdk.CollectionConverters._

case class ProxyHttpApp(client: Client, tracing: OpenTracing) {

  import tracing.aspects._

  def routes: HttpApp[Any, Throwable] =
    Http.collectZIO { case Method.GET -> _ / "statuses" =>
      (for {
        _        <- ZIO.unit @@ tag(Tags.SPAN_KIND.getKey, Tags.SPAN_KIND_CLIENT)
        _        <- ZIO.unit @@ tag(Tags.HTTP_METHOD.getKey, GET.method)
        _        <- ZIO.unit @@ setBaggageItem("proxy-baggage-item-key", "proxy-baggage-item-value")
        carrier   = new TextMapAdapter(mutable.Map.empty[String, String].asJava)
        _        <- tracing.inject(HttpHeadersFormat, carrier)
        headers  <- extractHeaders(carrier)
        statuses <- client.status(headers)
      } yield Response.json(statuses.toJson)) @@ root("/statuses")
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
