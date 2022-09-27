package zio.telemetry.opentracing.example.http

import io.opentracing.propagation.Format.Builtin.{ HTTP_HEADERS => HttpHeadersFormat }
import io.opentracing.propagation.TextMapAdapter
import zhttp.http.{ !!, ->, /, Http, HttpApp, Method, Response }
import zio.json.EncoderOps
import zio.telemetry.opentracing._
import zio.telemetry.opentracing.example.http.{ Status => ServiceStatus }
import zio._

import scala.jdk.CollectionConverters._

case class BackendHttpApp(tracing: OpenTracing) {

  def routes: HttpApp[Any, Throwable] =
    Http.collectZIO { case request @ Method.GET -> !! / "status" =>
      val headers = request.headers.toList.toMap

      tracing
        .spanFrom(HttpHeadersFormat, new TextMapAdapter(headers.asJava), "/status")(ZIO.unit)
        .as(Response.json(ServiceStatus.up("backend").toJson))
    }

}

object BackendHttpApp {

  val live: URLayer[OpenTracing, BackendHttpApp] =
    ZLayer.fromFunction(BackendHttpApp.apply _)

}
