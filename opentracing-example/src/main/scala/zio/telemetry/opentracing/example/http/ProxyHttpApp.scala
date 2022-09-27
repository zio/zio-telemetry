package zio.telemetry.opentracing.example.http

import io.opentracing.propagation.Format.Builtin.{ HTTP_HEADERS => HttpHeadersFormat }
import io.opentracing.propagation.TextMapAdapter
import io.opentracing.tag.Tags
import sttp.model.Method.GET
import sttp.model.Uri
import zhttp.http.{ !!, ->, /, Http, HttpApp, Method, Response }
import zio.json.EncoderOps
import zio.telemetry.opentracing.OpenTracing
import zio._

import scala.collection.mutable
import scala.jdk.CollectionConverters._

case class ProxyHttpApp(tracing: OpenTracing) {

  def statuses(backendUri: Uri): HttpApp[Any, Throwable] =
    Http.collectZIO { case Method.GET -> !! / "statuses" =>
      tracing
        .root("/statuses")(
          for {
            _       <- tracing.tag(Tags.SPAN_KIND.getKey, Tags.SPAN_KIND_CLIENT)(ZIO.unit)
            _       <- tracing.tag(Tags.HTTP_METHOD.getKey, GET.method)(ZIO.unit)
            _       <- tracing.setBaggageItem("proxy-baggage-item-key", "proxy-baggage-item-value")(ZIO.unit)
            buffer   = new TextMapAdapter(mutable.Map.empty[String, String].asJava)
            _       <- tracing.inject(HttpHeadersFormat, buffer)
            headers <- extractHeaders(buffer)
            up       = Status.up("proxy")
            res     <- Client
                         .status(backendUri.withPath("status"), headers)
                         .map { res =>
                           val status   = res.body.getOrElse(Status.down("backend"))
                           val statuses = Statuses(List(status, up))
                           Response.json(statuses.toJson)
                         }
          } yield res
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

  val live: URLayer[OpenTracing, ProxyHttpApp] =
    ZLayer.fromFunction(ProxyHttpApp.apply _)

}
