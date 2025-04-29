package zio.telemetry.opentelemetry.instrumentation.example.http

import zio._
import zio.http._
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing

case class ProxyHttpApp(openTelemetry: OpenTelemetry, client: BackendClient, tracing: Tracing) {

  val routes =
    Routes(
      Method.GET / "proxy" ->
        handler {
          openTelemetry.autoinstrumented(
            proxy @@ tracing.aspects.span("proxy")
          )
        }
    )

  private def proxy: UIO[Response] =
    for {
      backendResponse <- client.exampleEndpoint
                           .map(text => Response.text(text))
                           .catchAll(_ => ZIO.succeed(Response.badRequest))
      _               <- ZIO.logInfo("proxy processing finished on proxy")
    } yield backendResponse

}

object ProxyHttpApp {

  val live: URLayer[OpenTelemetry with BackendClient with Tracing, ProxyHttpApp] =
    ZLayer.fromFunction(ProxyHttpApp.apply _)

}
