package zio.telemetry.opentracing.example.http

import io.opentracing.propagation.Format.Builtin.{ HTTP_HEADERS => HttpHeadersFormat }
import io.opentracing.propagation.TextMapAdapter
import zhttp.http.{ !!, ->, /, Http, HttpApp, Method, Response }
import zio.json.EncoderOps
import zio.telemetry.opentracing._
import zio.telemetry.opentracing.example.http.{ Status => ServiceStatus }
import zio.{ ZIO, ZLayer }

import scala.jdk.CollectionConverters._

object BackendApp {
  def status(service: ZLayer[Any, Throwable, OpenTracing.Service]): HttpApp[Any, Throwable] =
    Http.collectZIO { case request @ Method.GET -> !! / "status" =>
      val headers = request.headers.toList.toMap
      ZIO.unit
        .spanFrom(HttpHeadersFormat, new TextMapAdapter(headers.asJava), "/status")
        .as(Response.json(ServiceStatus.up("backend").toJson))
        .provideSomeLayer(service)
    }
}
