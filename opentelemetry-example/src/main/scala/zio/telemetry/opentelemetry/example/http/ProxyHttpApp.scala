package zio.telemetry.opentelemetry.example.http

import io.opentelemetry.api.trace.{SpanKind, StatusCode}
import zio._
import zio.http._
import zio.json.EncoderOps
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.context.OutgoingContextCarrier
import zio.telemetry.opentelemetry.tracing.{StatusMapper, Tracing}

case class ProxyHttpApp(openTelemetry: OpenTelemetry, client: BackendClient, tracing: Tracing) {

  import tracing.aspects._

  private val statusMapper: StatusMapper[Throwable, Any] =
    StatusMapper.failureThrowable(_ => StatusCode.UNSET)

  val routes =
    Routes(
      Method.GET / "statuses" ->
        handler {
          statuses @@ root("/statuses", SpanKind.SERVER, statusMapper = statusMapper)
        }
    )

  def statuses: UIO[Response] = {
    val carrier = OutgoingContextCarrier.default()

    openTelemetry.baggage.set("proxy-baggage", "value from proxy")(
      for {
        _        <- tracing.setAttribute("http.method", "get")
        _        <- tracing.addEvent("proxy-event")
        _        <- openTelemetry.propagate(carrier)
        statuses <- client.status(carrier.kernel.toMap).catchAll(_ => ZIO.succeed(BackendStatuses(List.empty)))
        _        <- ZIO.logInfo("statuses processing finished on proxy")
      } yield Response.json(statuses.toJson)
    )
  }

}

object ProxyHttpApp {

  val live: URLayer[OpenTelemetry with BackendClient with Tracing, ProxyHttpApp] =
    ZLayer.fromFunction(ProxyHttpApp.apply _)

}
