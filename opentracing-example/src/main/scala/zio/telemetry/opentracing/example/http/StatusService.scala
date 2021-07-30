package zio.telemetry.opentracing.example.http

import io.opentracing.propagation.Format.Builtin.{ HTTP_HEADERS => HttpHeadersFormat }
import io.opentracing.propagation.TextMapAdapter
import zio.clock.Clock
import zio.magic._
import zio.telemetry.opentracing.example.http.{ Status => ServiceStatus }
import zio.telemetry.opentracing._
import zio.{ ZIO, ZLayer }
import zhttp.http._
import zio.json._

import scala.jdk.CollectionConverters._

object StatusService {
  def status(service: ZLayer[Clock, Throwable, OpenTracing]): HttpApp[Clock, Throwable] =
    Http.collectM {
      case request@Method.GET -> Root / "status" =>
        val headers = request.headers.map(h => h.name.toString -> h.value.toString).toMap
        ZIO.unit
          .spanFrom(HttpHeadersFormat, new TextMapAdapter(headers.asJava), "/status")
          .as(Response.jsonString(ServiceStatus.up("backend").toJson))
          .inject(service, Clock.live)
    }
}
