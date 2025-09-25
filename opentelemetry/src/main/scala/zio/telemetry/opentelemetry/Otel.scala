package zio.telemetry.opentelemetry

import io.opentelemetry.api.{GlobalOpenTelemetry, OpenTelemetry => JOpenTelemetry}
import zio._
import zio.metrics.{MetricClient, MetricListener}
import zio.telemetry.opentelemetry.context.internal.ContextStorage
import zio.telemetry.opentelemetry.context.ContextPropagator
import zio.telemetry.opentelemetry.logs.Logger
import zio.telemetry.opentelemetry.metrics.Meter
import zio.telemetry.opentelemetry.metrics.internal.{Instrument, InstrumentRegistry, OtelMetricListener}
import zio.telemetry.opentelemetry.trace.Tracer

/**
 * The entrypoint to telemetry functionality for tracer, metrics, logger and baggage.
 */
object Otel {

  /**
   * A global singleton for the entrypoint to telemetry functionality for tracer, metrics, logger and baggage. Should be
   * used with <a href="https://opentelemetry.io/docs/instrumentation/java/automatic/agent-config/">SDK
   * Autoconfiguration</a> module and/or <a
   * href="https://github.com/open-telemetry/opentelemetry-java-instrumentation">Automatic instrumentation</a> Java
   * agent.
   *
   * @see
   *   `autoinstrumented` in [[zio.telemetry.opentelemetry.OpenTelemetry]]
   */
  def global(logAnnotated: Boolean = false)(implicit trace: Trace): TaskLayer[OpenTelemetry] =
    ZLayer.scoped {
      for {
        underlying <- ZIO.attempt(GlobalOpenTelemetry.get())
        propagator  = ContextPropagator.fromJava(underlying.getPropagators)
      } yield new OpenTelemetry.Sdk(ContextStorage.JavaOtelThreadLocal, underlying, propagator, logAnnotated)
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
  def custom(
    ctxPropagator: ContextPropagator = ContextPropagator.default,
    logAnnotated: Boolean = false
  )(
    zio: => ZIO[Scope, Throwable, JOpenTelemetry]
  )(implicit trace: Trace): TaskLayer[OpenTelemetry] =
    ZLayer.scoped {
      for {
        underlying <- zio
        ctxStorage <- ContextStorage.zioFiberRefScoped
      } yield new OpenTelemetry.Sdk(ctxStorage, underlying, ctxPropagator, logAnnotated)
    }

  def custom(zio: => ZIO[Scope, Throwable, JOpenTelemetry])(implicit trace: Trace): TaskLayer[OpenTelemetry] =
    custom()(zio)

  def noop(logAnnotated: Boolean = false)(implicit trace: Trace): TaskLayer[OpenTelemetry] =
    ZLayer.scoped {
      for {
        underlying <- ZIO.attempt(JOpenTelemetry.noop())
        ctxStorage <- ContextStorage.zioFiberRefScoped
      } yield new OpenTelemetry.Sdk(ctxStorage, underlying, ContextPropagator.noop, logAnnotated)
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
  def tracer(
    instrumentationScopeName: String,
    instrumentationVersion: Option[String] = None,
    schemaUrl: Option[String] = None,
    logAnnotated: Boolean = false
  )(implicit trace: Trace): URLayer[OpenTelemetry, Tracer] = {
    def buildTracer(openTelemetry: JOpenTelemetry) = {
      val builder = openTelemetry.tracerBuilder(instrumentationScopeName)

      instrumentationVersion.foreach(builder.setInstrumentationVersion)
      schemaUrl.foreach(builder.setSchemaUrl)

      builder.build
    }

    ZLayer {
      for {
        openTelemetry <- ZIO.service[OpenTelemetry]
        jtracer        = buildTracer(openTelemetry.unsafe.asJava)
        tracer         = Tracer.make(jtracer, openTelemetry.ctxStorage, logAnnotated)
      } yield tracer
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
  )(implicit trace: Trace): URLayer[OpenTelemetry, Meter] = {
    def buildMeter(openTelemetry: JOpenTelemetry) = {
      val builder = openTelemetry.meterBuilder(instrumentationScopeName)

      instrumentationVersion.foreach(builder.setInstrumentationVersion)
      schemaUrl.foreach(builder.setSchemaUrl)

      builder.build()
    }

    ZLayer {
      for {
        openTelemetry <- ZIO.service[OpenTelemetry]
        jmeter         = buildMeter(openTelemetry.unsafe.asJava)
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
  def installLogger(
    instrumentationScopeName: String,
    logLevel: LogLevel = LogLevel.Info
  )(implicit trace: Trace): URLayer[OpenTelemetry, Unit] =
    ZLayer.scoped {
      for {
        openTelemetry <- ZIO.service[OpenTelemetry]
        loggerProvider = openTelemetry.unsafe.asJava.getLogsBridge
        _             <- Logger.install(loggerProvider, openTelemetry.ctxStorage, instrumentationScopeName, logLevel)
      } yield ()
    }

  /**
   * Returns an instance of `ZIO.ZLogger` that is configured to propagate log records as OTEL Log signals.
   *
   * It may be useful when you need to execute an effect with the specified logger. See `ZIO.withLogger`.
   */
  def zioLogger(
    instrumentationScopeName: String
  )(implicit trace: Trace): URLayer[OpenTelemetry, ZLogger[String, Unit]] =
    ZLayer.scoped {
      for {
        openTelemetry <- ZIO.service[OpenTelemetry]
        loggerProvider = openTelemetry.unsafe.asJava.getLogsBridge
        logger         = Logger.zioLogger(instrumentationScopeName)(openTelemetry.ctxStorage, loggerProvider)
      } yield logger
    }

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
  )(implicit trace: Trace): URLayer[OpenTelemetry, Unit] = {
    def buildMeter(openTelemetry: JOpenTelemetry) = {
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
          jmeter         = buildMeter(openTelemetry.unsafe.asJava)
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
