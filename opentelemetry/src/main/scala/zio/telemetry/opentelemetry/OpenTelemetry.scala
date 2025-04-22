package zio.telemetry.opentelemetry

import io.opentelemetry.api
import io.opentelemetry.context.Context
import zio._
import zio.metrics.{MetricClient, MetricListener}
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.logging.Logging
import zio.telemetry.opentelemetry.metrics.Meter
import zio.telemetry.opentelemetry.metrics.internal.{Instrument, InstrumentRegistry, OtelMetricListener}
import zio.telemetry.opentelemetry.tracing.Tracing

final class OpenTelemetry(
  private[opentelemetry] val underlying: api.OpenTelemetry,
  private[opentelemetry] val ctxStorage: ContextStorage
) {

  def asJava: api.OpenTelemetry =
    underlying

  def autoinstrumented[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    ctxStorage.locally(Context.current())(zio)

}

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
  val global: TaskLayer[OpenTelemetry] =
    ZLayer.scoped {
      for {
        underlying <- ZIO.attempt(api.GlobalOpenTelemetry.get())
        ctxStorage <- ContextStorage.rootScoped
      } yield new OpenTelemetry(underlying, ctxStorage)
    }

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
  def custom(zio: => ZIO[Scope, Throwable, api.OpenTelemetry]): TaskLayer[OpenTelemetry] =
    ZLayer.scoped {
      for {
        underlying <- zio
        ctxStorage <- ContextStorage.rootScoped
      } yield new OpenTelemetry(underlying, ctxStorage)
    }

  val noop: TaskLayer[OpenTelemetry] =
    ZLayer.scoped {
      for {
        underlying <- ZIO.attempt(api.OpenTelemetry.noop())
        ctxStorage <- ContextStorage.rootScoped
      } yield new OpenTelemetry(underlying, ctxStorage)
    }

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
    schemaUrl: Option[String] = None,
    logAnnotated: Boolean = false
  ): URLayer[OpenTelemetry, Tracing] = {
    def buildTracer(openTelemetry: api.OpenTelemetry) = {
      val builder = openTelemetry.tracerBuilder(instrumentationScopeName)

      instrumentationVersion.foreach(builder.setInstrumentationVersion)
      schemaUrl.foreach(builder.setSchemaUrl)

      builder.build
    }

    ZLayer.scoped {
      for {
        openTelemetry <- ZIO.service[OpenTelemetry]
        tracer         = buildTracer(openTelemetry.underlying)
        tracing       <- Tracing.scoped(tracer, openTelemetry.ctxStorage, logAnnotated)
      } yield tracing

    }
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
  def metrics(
    instrumentationScopeName: String,
    instrumentationVersion: Option[String] = None,
    schemaUrl: Option[String] = None,
    logAnnotated: Boolean = false
  ): URLayer[OpenTelemetry, Meter] = {
    def buildMeter(openTelemetry: api.OpenTelemetry) = {
      val builder = openTelemetry.meterBuilder(instrumentationScopeName)

      instrumentationVersion.foreach(builder.setInstrumentationVersion)
      schemaUrl.foreach(builder.setSchemaUrl)

      builder.build()
    }

    ZLayer {
      for {
        openTelemetry <- ZIO.service[OpenTelemetry]
        jmeter         = buildMeter(openTelemetry.underlying)
        builder        = Instrument.Builder.make(jmeter, openTelemetry.ctxStorage, logAnnotated)
        meter          = Meter.make(builder)
      } yield meter
    }
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
  ): URLayer[OpenTelemetry, Unit] =
    ZLayer.scoped {
      for {
        openTelemetry <- ZIO.service[OpenTelemetry]
        loggerProvider = openTelemetry.underlying.getLogsBridge
        _             <- Logging.make(loggerProvider, openTelemetry.ctxStorage, instrumentationScopeName, logLevel)
      } yield ()
    }

  /**
   * Use when you need to pass contextual information between spans.
   *
   * @param logAnnotated
   *   propagate ZIO log annotations as Baggage key/values if it is set to true
   */
  def baggage(logAnnotated: Boolean = false): URLayer[OpenTelemetry, Baggage] =
    ZLayer(ZIO.serviceWith[OpenTelemetry](openTelemetry => Baggage.make(openTelemetry.ctxStorage, logAnnotated)))

  /**
   * Use when you want to allow a seamless integration with ZIO runtime and JVM metrics.
   *
   * By default this layer enables the propagation of ZIO runtime metrics only. For JVM metrics you need to provide
   * `DefaultJvmMetrics.live.unit`.
   */
  def zioMetrics(
    instrumentationScopeName: String,
    instrumentationVersion: Option[String] = None,
    schemaUrl: Option[String] = None
  ): URLayer[OpenTelemetry, Unit] = {
    def buildMeter(openTelemetry: api.OpenTelemetry) = {
      val builder = openTelemetry.meterBuilder(instrumentationScopeName)

      instrumentationVersion.foreach(builder.setInstrumentationVersion)
      schemaUrl.foreach(builder.setSchemaUrl)

      builder.build()
    }

    val metricListenerLifecycleLayer = ZLayer.scoped {
      ZIO.serviceWithZIO[MetricListener] { metricListener =>
        Unsafe.unsafe { implicit unsafe =>
          ZIO.acquireRelease(
            ZIO.succeed(MetricClient.addListener(metricListener))
          )(_ => ZIO.succeed(MetricClient.removeListener(metricListener)))
        }
      }
    }

    val registryLayer =
      ZLayer {
        for {
          openTelemetry <- ZIO.service[OpenTelemetry]
          jmeter         = buildMeter(openTelemetry.underlying)
          builder        = Instrument.Builder.make(jmeter, openTelemetry.ctxStorage)
          registry       = InstrumentRegistry.concurrent(builder)
        } yield registry
      }

    val zioMetricsLayer = ZLayer(ZIO.serviceWith[InstrumentRegistry](OtelMetricListener.zioMetrics(_)))

    Runtime.enableRuntimeMetrics >>>
      registryLayer >>>
      zioMetricsLayer >>>
      metricListenerLifecycleLayer
  }

}
