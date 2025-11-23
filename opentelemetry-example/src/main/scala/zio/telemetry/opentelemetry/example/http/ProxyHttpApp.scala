package zio.telemetry.opentelemetry.example.http

import io.opentelemetry.api.trace.StatusCode
import zio._
import zio.http._
import zio.json.EncoderOps
import zio.telemetry.opentelemetry.core.OpenTelemetry
import zio.telemetry.opentelemetry.core.context.OutgoingContextCarrier
import zio.telemetry.opentelemetry.core.trace.{StatusMapper, Tracer}

case class ProxyHttpApp(openTelemetry: OpenTelemetry, client: BackendClient, tracer: Tracer) {

  val routes: Routes[Any, Nothing] =
    Routes(
      Method.GET / "statuses" ->
        handler {
          tracer.root(
            "/statuses",
            statusMapper = StatusMapper.failureThrowable(_ => StatusCode.UNSET)
          ) { span =>
            val carrier = OutgoingContextCarrier.default()

            openTelemetry.baggage.set("proxy-baggage", "value from proxy")(
              for {
                _        <- span.setAttribute("http.method", "get")
                _        <- span.addEvent("proxy-event")
                _        <- openTelemetry.propagate(carrier)
                statuses <- client.status(carrier.kernel.toMap).catchAll(_ => ZIO.succeed(BackendStatuses(List.empty)))
                _        <- ZIO.logInfo("statuses processing finished on proxy")
              } yield Response.json(statuses.toJson)
            )
          }
        }
    )

}

object ProxyHttpApp {

  val live: URLayer[OpenTelemetry with BackendClient with Tracer, ProxyHttpApp] =
    ZLayer.fromFunction(ProxyHttpApp.apply _)

}
