package zio.telemetry.opentracing.example.http

import io.opentracing.propagation.Format.Builtin.{HTTP_HEADERS => HttpHeadersFormat}
import io.opentracing.propagation.TextMapAdapter
import zio._
import zio.http._
import zio.json.EncoderOps
import zio.telemetry.opentracing._
import zio.telemetry.opentracing.example.http.{BackendStatus => ServiceStatus}

import scala.jdk.CollectionConverters._

case class BackendHttpApp(tracing: OpenTracing) {

  val routes: Routes[Any, Nothing] =
    Routes(
      Method.GET / "status" ->
        handler { request: Request =>
          val headers = request.headers.map(h => h.headerName -> h.renderedValue).toMap

          (ZIO.unit @@ tracing.aspects.spanFrom(HttpHeadersFormat, new TextMapAdapter(headers.asJava), "/status"))
            .as(Response.json(ServiceStatus.up("backend").toJson))
        }
    )

}

object BackendHttpApp {

  val live: URLayer[OpenTracing, BackendHttpApp] =
    ZLayer.fromFunction(BackendHttpApp.apply _)

}
