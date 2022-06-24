package zio.telemetry.opentracing.example.http

import io.opentracing.propagation.Format.Builtin.{ HTTP_HEADERS => HttpHeadersFormat }
import io.opentracing.propagation.TextMapAdapter
import io.opentracing.tag.Tags
import sttp.model.Method.GET
import sttp.model.Uri
import zhttp.http.{ !!, ->, /, Http, HttpApp, Method, Response }
import zio.json.EncoderOps
import zio.telemetry.opentracing.OpenTracing
import zio.{ UIO, ZIO, ZLayer }

import scala.collection.mutable
import scala.jdk.CollectionConverters._

object ProxyApp {
  def statuses(backendUri: Uri, service: ZLayer[Any, Throwable, OpenTracing.Service]): HttpApp[Any, Throwable] =
    Http.collectZIO { case Method.GET -> !! / "statuses" =>
      val zio =
        for {
          _       <- OpenTracing.tag(Tags.SPAN_KIND.getKey, Tags.SPAN_KIND_CLIENT)
          _       <- OpenTracing.tag(Tags.HTTP_METHOD.getKey, GET.method)
          _       <- OpenTracing.setBaggageItem("proxy-baggage-item-key", "proxy-baggage-item-value")
          buffer  <- ZIO.succeed(new TextMapAdapter(mutable.Map.empty[String, String].asJava))
          _       <- OpenTracing.inject(HttpHeadersFormat, buffer)
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

      zio
        .root("/statuses")
        .provideSomeLayer(service)
    }

  private def extractHeaders(adapter: TextMapAdapter): UIO[Map[String, String]] = {
    val m = mutable.Map.empty[String, String]
    ZIO
      .succeed(adapter.forEach { entry =>
        m.put(entry.getKey, entry.getValue)
        ()
      })
      .as(m.toMap)
  }

}
