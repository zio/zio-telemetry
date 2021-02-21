package zio.telemetry.opentelemetry.example.http

import io.circe.Encoder
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.api.trace.{ SpanKind, StatusCode }
import io.opentelemetry.context.propagation.TextMapPropagator
import io.opentelemetry.context.propagation.TextMapSetter
import org.http4s.circe.jsonEncoderOf
import org.http4s.dsl.Http4sDsl
import org.http4s.{ EntityEncoder, HttpRoutes }
import zio.UIO
import zio.interop.catz._
import zio.telemetry.opentelemetry.Tracing.root
import zio.telemetry.opentelemetry.Tracing

import scala.collection.mutable

object StatusesService {

  val dsl: Http4sDsl[AppTask] = Http4sDsl[AppTask]
  import dsl._

  implicit def encoder[A: Encoder]: EntityEncoder[AppTask, A] = jsonEncoderOf[AppTask, A]

  val propagator: TextMapPropagator                      = W3CTraceContextPropagator.getInstance()
  val setter: TextMapSetter[mutable.Map[String, String]] = (carrier, key, value) => carrier.update(key, value)

  val errorMapper: PartialFunction[Throwable, StatusCode] = { case _ => StatusCode.UNSET }

  val routes: HttpRoutes[AppTask] = HttpRoutes.of[AppTask] { case GET -> Root / "statuses" =>
    root("/statuses", SpanKind.SERVER, errorMapper) {
      for {
        carrier <- UIO(mutable.Map[String, String]().empty)
        _       <- Tracing.setAttribute("http.method", "get")
        _       <- Tracing.addEvent("proxy-event")
        _       <- Tracing.inject(propagator, carrier, setter)
        res     <- Client.status(carrier.toMap).flatMap(Ok(_))
      } yield res
    }
  }

}
