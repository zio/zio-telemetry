package zio.telemetry.opentelemetry.example

import zio.config.magnolia._
import zio.config.typesafe.TypesafeConfig
import zio.telemetry.opentelemetry.example.config.AppConfig
import zio.telemetry.opentelemetry.example.http.{BackendHttpApp, BackendHttpServer}
import zio._
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.example.otel.OtelSdk
import zio.telemetry.opentelemetry.metrics.Meter

object BackendApp extends ZIOAppDefault {

  private val configLayer = TypesafeConfig.fromResourcePath(descriptor[AppConfig])

  private val instrumentationScopeName = "zio.telemetry.opentelemetry.example.BackendApp"
  private val resourceName             = "opentelemetry-example-backend"

  val observableCounterLayer: RLayer[Meter, Unit] =
    ZLayer.scoped(
      ZIO.serviceWithZIO[Meter](_.observableCounter("foo")(_.record(1L)))
    )

  override def run: ZIO[Scope, Any, ExitCode] =
    ZIO
      .serviceWithZIO[BackendHttpServer](_.start.exitCode)
      .provide(
        configLayer,
        BackendHttpServer.live,
        BackendHttpApp.live,
        OtelSdk.custom(resourceName),
        OpenTelemetry.tracing(instrumentationScopeName),
        OpenTelemetry.logging(instrumentationScopeName),
        OpenTelemetry.meter(instrumentationScopeName),
        observableCounterLayer,
        Baggage.live(),
        ContextStorage.fiberRef
      )

}
