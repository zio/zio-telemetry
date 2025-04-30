package zio.telemetry.opentelemetry

import io.opentelemetry.api
import io.opentelemetry.context.Context
import zio._
import zio.metrics.{MetricClient, MetricListener}
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.context.internal.ContextStorage
import zio.telemetry.opentelemetry.context.{ContextPropagator, IncomingContextCarrier, OutgoingContextCarrier}
import zio.telemetry.opentelemetry.logging.Logging
import zio.telemetry.opentelemetry.metrics.Meter
import zio.telemetry.opentelemetry.metrics.internal.{Instrument, InstrumentRegistry, OtelMetricListener}
import zio.telemetry.opentelemetry.tracing.Tracing

trait OpenTelemetry { self =>

  def autoinstrumented[R, E, A](zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    ctxStorage.locally(Context.current())(zio)

  def propagate[C](carrier: OutgoingContextCarrier[C]): UIO[Unit] =
    ctxStorage.get.map(ctxPropagator.instance.inject(_, carrier.kernel, carrier)).unit

  def continue[R, E, A, C](carrier: IncomingContextCarrier[C])(zio: => ZIO[R, E, A]): ZIO[R, E, A] =
    ctxStorage.locally(ctxPropagator.instance.extract(Context.root, carrier.kernel, carrier))(zio)

  def asJava: api.OpenTelemetry =
    underlying

  /**
   * Use when you need to pass contextual information between spans.
   */
  val baggage: Baggage =
    Baggage.make(ctxStorage)

  val ctxPropagator: ContextPropagator =
    ContextPropagator.default

  /**
   * Configure Baggage instance
   *
   * @param logAnnotated
   *   propagate ZIO log annotations as Baggage key/values if it is set to true
   */
  def withBaggage(logAnnotated: Boolean): OpenTelemetry

  def withContextPropagator(propagator: ContextPropagator): OpenTelemetry

  private[opentelemetry] def underlying: api.OpenTelemetry

  private[opentelemetry] def ctxStorage: ContextStorage

  object aspects {

    def autoinstrumented: ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
      new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
        override def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          self.autoinstrumented(zio)
      }

    def continue[C](carrier: IncomingContextCarrier[C]): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
      new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
        override def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          self.continue(carrier)(zio)
      }

  }

}

/**
 * The entrypoint to telemetry functionality for tracing, metrics, logging and baggage.
 */
object OpenTelemetry {

  class OpenTelemetrySdk private[opentelemetry] (
    val underlying: api.OpenTelemetry,
    val ctxStorage: ContextStorage
  ) extends OpenTelemetry {

    override def withBaggage(logAnnotated: Boolean): OpenTelemetrySdk =
      new OpenTelemetrySdk(underlying, ctxStorage) {
        override val baggage: Baggage = Baggage.make(ctxStorage, logAnnotated)
      }

    override def withContextPropagator(propagator: ContextPropagator): OpenTelemetrySdk =
      new OpenTelemetrySdk(underlying, ctxStorage) {
        override val ctxPropagator = propagator
      }

  }

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
        propagator  = ContextPropagator.fromJava(underlying.getPropagators)
      } yield new OpenTelemetrySdk(underlying, ContextStorage.JavaOtelThreadLocal).withContextPropagator(propagator)
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
        ctxStorage <- ContextStorage.zioFiberRefScoped
      } yield new OpenTelemetrySdk(underlying, ctxStorage)
    }

  val noop: TaskLayer[OpenTelemetry] =
    ZLayer.scoped {
      for {
        underlying <- ZIO.attempt(api.OpenTelemetry.noop())
        ctxStorage <- ContextStorage.zioFiberRefScoped
      } yield new OpenTelemetrySdk(underlying, ctxStorage).withContextPropagator(ContextPropagator.noop)
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
        tracer         = buildTracer(openTelemetry.asJava)
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
        jmeter         = buildMeter(openTelemetry.asJava)
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
        loggerProvider = openTelemetry.asJava.getLogsBridge
        _             <- Logging.make(loggerProvider, openTelemetry.ctxStorage, instrumentationScopeName, logLevel)
      } yield ()
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
          jmeter         = buildMeter(openTelemetry.asJava)
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
