package zio.telemetry.opentelemetry

import io.opentelemetry.api
import zio._
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.logging.Logging
import zio.telemetry.opentelemetry.metrics.Meter
import zio.telemetry.opentelemetry.tracing.Tracing

object OpenTelemetry {

  def global: TaskLayer[api.OpenTelemetry] =
    ZLayer(ZIO.attempt(api.GlobalOpenTelemetry.get()))

  def custom(zio: => ZIO[Scope, Throwable, api.OpenTelemetry]): TaskLayer[api.OpenTelemetry] =
    ZLayer.scoped(zio)

  def noop: TaskLayer[api.OpenTelemetry] =
    ZLayer.succeed(api.OpenTelemetry.noop())

  def tracing(
    instrumentationScopeName: String,
    instrumentationVersion: Option[String] = None,
    schemaUrl: Option[String] = None
  ): URLayer[api.OpenTelemetry with ContextStorage, Tracing] = {
    val tracerLayer = ZLayer(
      ZIO.serviceWith[api.OpenTelemetry] { openTelemetry =>
        val builder = openTelemetry.tracerBuilder(instrumentationScopeName)

        instrumentationVersion.foreach(builder.setInstrumentationVersion)
        schemaUrl.foreach(builder.setSchemaUrl)

        builder.build
      }
    )

    tracerLayer >>> Tracing.live
  }

  def meter(
    instrumentationScopeName: String,
    instrumentationVersion: Option[String] = None,
    schemaUrl: Option[String] = None
  ): URLayer[api.OpenTelemetry with ContextStorage, Meter] = {
    val meterLayer = ZLayer(
      ZIO.serviceWith[api.OpenTelemetry] { openTelemetry =>
        val builder = openTelemetry.meterBuilder(instrumentationScopeName)

        instrumentationVersion.foreach(builder.setInstrumentationVersion)
        schemaUrl.foreach(builder.setSchemaUrl)

        builder.build()
      }
    )

    meterLayer >>> Meter.live
  }

  def logging(
    instrumentationScopeName: String,
    logLevel: LogLevel = LogLevel.Info
  ): URLayer[api.OpenTelemetry with ContextStorage, Unit] = {
    val loggerProviderLayer = ZLayer(ZIO.serviceWith[api.OpenTelemetry](_.getLogsBridge))

    loggerProviderLayer >>> Logging.live(instrumentationScopeName, logLevel)
  }

}
