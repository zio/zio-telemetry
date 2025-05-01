package zio.telemetry.opentelemetry.instrumentation.example.http

import zio._
import zio.http._
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.trace.Tracer

case class ProxyHttpApp(openTelemetry: OpenTelemetry, client: BackendClient, tracer: Tracer) {

  val routes =
    Routes(
      Method.GET / "proxy" ->
        handler {
          proxy @@
            tracer.aspects.span("proxy") @@
            openTelemetry.aspects.autoinstrumented
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

  val live: URLayer[OpenTelemetry with BackendClient with Tracer, ProxyHttpApp] =
    ZLayer.fromFunction(ProxyHttpApp.apply _)

}
