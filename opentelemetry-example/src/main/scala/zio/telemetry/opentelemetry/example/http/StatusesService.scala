package zio.telemetry.opentelemetry.example.http

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.api.trace.{ SpanKind, StatusCode }
import io.opentelemetry.context.propagation.{ TextMapPropagator, TextMapSetter }
import zio.UIO
import zio.telemetry.opentelemetry.Tracing.root
import zio.telemetry.opentelemetry.Tracing
import zhttp.http._
import zio.json._

import scala.collection.mutable

object StatusesService {

  val propagator: TextMapPropagator                      = W3CTraceContextPropagator.getInstance()
  val setter: TextMapSetter[mutable.Map[String, String]] = (carrier, key, value) => carrier.update(key, value)

  val errorMapper: PartialFunction[Throwable, StatusCode] = { case _ => StatusCode.UNSET }

  val routes: HttpApp[Client with Tracing, Throwable] = Http.collectM {
    case Method.GET -> Root / "statuses" =>
      root("/statuses", SpanKind.SERVER, errorMapper) {
        for {
          carrier <- UIO(mutable.Map[String, String]().empty)
          _ <- Tracing.setAttribute("http.method", "get")
          _ <- Tracing.addEvent("proxy-event")
          _ <- Tracing.inject(propagator, carrier, setter)
          res <- Client.status(carrier.toMap).map(s => Response.jsonString(s.toJson))
        } yield res
      }
  }

}
