package zio.telemetry.opentracing.example.http

import io.opentracing.propagation.Format.Builtin.{ HTTP_HEADERS => HttpHeadersFormat }
import io.opentracing.propagation.TextMapAdapter
import io.opentracing.tag.Tags
import sttp.model.Uri
import sttp.model.Method.GET
import zio.clock.Clock
import zio.telemetry.opentracing.OpenTracing
import zio.{ UIO, ZIO, ZLayer }
import zhttp.http.HttpApp
import zhttp.http._
import zio.json._

import scala.collection.mutable
import scala.jdk.CollectionConverters._

object StatusesService {
  def statuses(backendUri: Uri, service: ZLayer[Clock, Throwable, Clock with OpenTracing]): HttpApp[Clock, Throwable] = {
    HttpApp.collectM {
      case Method.GET -> Root / "statuses" =>
        val zio =
          for {
            _       <- OpenTracing.tag(Tags.SPAN_KIND.getKey, Tags.SPAN_KIND_CLIENT)
            _       <- OpenTracing.tag(Tags.HTTP_METHOD.getKey, GET.method)
            _       <- OpenTracing.setBaggageItem("proxy-baggage-item-key", "proxy-baggage-item-value")
            buffer  <- UIO.succeed(new TextMapAdapter(mutable.Map.empty[String, String].asJava))
            _       <- OpenTracing.inject(HttpHeadersFormat, buffer)
            headers <- extractHeaders(buffer)
            up       = Status.up("proxy")
            res     <- Client
                         .status(backendUri.withPath("status"), headers)
                         .map(_.body)
                         .flatMap {
                           case Right(s) => ZIO.succeed(Response.jsonString(Statuses(List(s, up)).toJson))
                           case _        => ZIO.succeed(Response.jsonString(Statuses(List(Status.down("backend"), up)).toJson))
                         }
          } yield res

        zio
          .root("/statuses")
          .provideLayer(service)
    }
  }

  private def extractHeaders(adapter: TextMapAdapter): UIO[Map[String, String]] = {
    val m = mutable.Map.empty[String, String]
    UIO(adapter.forEach { entry =>
      m.put(entry.getKey, entry.getValue)
      ()
    }).as(m.toMap)
  }

}
