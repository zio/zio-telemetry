package zio.telemetry.opentelemetry

import io.opentelemetry.api
import zio._
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.logging.Logging
import zio.telemetry.opentelemetry.metrics.Meter
import zio.telemetry.opentelemetry.tracing.Tracing

/**
 * The entrypoint to telemetry functionality for tracing, metrics, logging and baggage.
 */
object OpenTelemetry {

  /**
   * A global singleton for the entrypoint to telemetry functionality for tracing, metrics, logging and baggage. Should
   * be used with <a href="https://opentelemetry.io/docs/instrumentation/java/automatic/agent-config/">SDK
   * Autoconfiguration</a> module and/or <a href="">Automatic instrumentation</a> Java agent.
   *
   * @see
   *   <a href="https://zio.dev/zio-telemetry/opentelemetry/#usage-with-opentelemetry-automatic-instrumentation">Usage
   *   with OpenTelemetry automatic instrumentation</a>
   */
  val global: TaskLayer[api.OpenTelemetry] =
    ZLayer(ZIO.attempt(api.GlobalOpenTelemetry.get()))

  /**
   * Use when you need to configure an instance of OpenTelemetry programmatically.
   *
   * Example:
   * [[https://github.com/zio/zio-telemetry/blob/series/2.x/opentelemetry-example/src/main/scala/zio/telemetry/opentelemetry/example/otel/OtelSdk.scala]]
   *
   * @param zio
   *   scoped ZIO value that returns a configured instance of [[io.opentelemetry.api.OpenTelemetry]], thus ensuring that
   *   the returned instance will be closed.
   */
  def custom(zio: => ZIO[Scope, Throwable, api.OpenTelemetry]): TaskLayer[api.OpenTelemetry] =
    ZLayer.scoped(zio)

  val noop: ULayer[api.OpenTelemetry] =
    ZLayer.succeed(api.OpenTelemetry.noop())

  /**
   * Use when you need to instrument spans manually.
   *
   * @param instrumentationScopeName
   *   name uniquely identifying the instrumentation scope, such as the instrumentation library, package, or fully
   *   qualified class name
   * @param instrumentationVersion
   *   version of the instrumentation scope (e.g., "1.0.0")
   * @param schemaUrl
   *   schema URL
   */
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

  /**
   * Use when you need to instrument metrics manually.
   *
   * @param instrumentationScopeName
   *   name uniquely identifying the instrumentation scope, such as the instrumentation library, package, or fully
   *   qualified class name
   * @param instrumentationVersion
   *   version of the instrumentation scope (e.g., "1.0.0")
   * @param schemaUrl
   *   schema URL
   */
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

  /**
   * Use when you need to propagate calls to `ZIO.log*` as OTEL Log signals.
   *
   * @param instrumentationScopeName
   *   name uniquely identifying the instrumentation scope, such as the instrumentation library, package, or fully
   *   qualified class name
   * @param logLevel
   *   configures the logger to propagate the log records only when the log level is more than specified
   */
  def logging(
    instrumentationScopeName: String,
    logLevel: LogLevel = LogLevel.Info
  ): URLayer[api.OpenTelemetry with ContextStorage, Unit] = {
    val loggerProviderLayer = ZLayer(ZIO.serviceWith[api.OpenTelemetry](_.getLogsBridge))

    loggerProviderLayer >>> Logging.live(instrumentationScopeName, logLevel)
  }

  /**
   * Use when you need to pass contextual information that between spans.
   *
   * @param logAnnotated
   *   propagate ZIO log annotations as Baggage key/values if it is set to true
   */
  def baggage(logAnnotated: Boolean = false): URLayer[ContextStorage, Baggage] =
    Baggage.live(logAnnotated)

}
