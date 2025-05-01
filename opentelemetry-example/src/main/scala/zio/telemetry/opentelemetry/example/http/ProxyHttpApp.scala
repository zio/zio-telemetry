package zio.telemetry.opentelemetry.example.http

import io.opentelemetry.api.trace.{SpanKind, StatusCode}
import zio._
import zio.http._
import zio.json.EncoderOps
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.context.OutgoingContextCarrier
import zio.telemetry.opentelemetry.trace.{StatusMapper, Tracer}

case class ProxyHttpApp(openTelemetry: OpenTelemetry, client: BackendClient, tracer: Tracer) {

  private val statusMapper: StatusMapper[Throwable, Any] =
    StatusMapper.failureThrowable(_ => StatusCode.UNSET)

  val routes =
    Routes(
      Method.GET / "statuses" ->
        handler {
          statuses @@ tracer.aspects.root("/statuses", SpanKind.SERVER, statusMapper = statusMapper)
        }
    )

  def statuses: UIO[Response] = {
    val carrier = OutgoingContextCarrier.default()

    openTelemetry.baggage.set("proxy-baggage", "value from proxy")(
      for {
        _        <- tracer.setAttribute("http.method", "get")
        _        <- tracer.addEvent("proxy-event")
        _        <- openTelemetry.propagate(carrier)
        statuses <- client.status(carrier.kernel.toMap).catchAll(_ => ZIO.succeed(BackendStatuses(List.empty)))
        _        <- ZIO.logInfo("statuses processing finished on proxy")
      } yield Response.json(statuses.toJson)
    )
  }

}

object ProxyHttpApp {

  val live: URLayer[OpenTelemetry with BackendClient with Tracer, ProxyHttpApp] =
    ZLayer.fromFunction(ProxyHttpApp.apply _)

}
