package zio.telemetry.opentelemetry

import io.opentelemetry.api.{GlobalOpenTelemetry, OpenTelemetry => JOpenTelemetry}
import io.opentelemetry.context.Context
import zio._
import zio.metrics.{MetricClient, MetricListener}
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.context.internal.ContextStorage
import zio.telemetry.opentelemetry.context.{ContextPropagator, IncomingContextCarrier, OutgoingContextCarrier}
import zio.telemetry.opentelemetry.logs.Logger
import zio.telemetry.opentelemetry.metrics.Meter
import zio.telemetry.opentelemetry.metrics.internal.{Instrument, InstrumentRegistry, OtelMetricListener}
import zio.telemetry.opentelemetry.trace.Tracer

trait OpenTelemetry { self =>

  /**
   * Use it exclusively together with
   * [[https://github.com/open-telemetry/opentelemetry-java-instrumentation OTEL Java Auto Instrumentation]]
   *
   * When your application is instrumented with the OTEL Java Agent, it automatically performs end-to-end context
   * propagation for any supported libraries you use. Manual instrumentation is also supported. Must be used only once
   * per entry point of your application.
   *
   * See for example:
   * [[https://github.com/zio/zio-telemetry/blob/v4.0.0-rc/opentelemetry-instrumentation-example/src/main/scala/zio/telemetry/opentelemetry/instrumentation/example/http/BackendHttpApp.scala]]
   *
   * {{{
   *  openTelemetry.autoinstrumented(
   *    zio @ tracer.aspects.span("internal")
   *  )
   * }}}
   *
   * @param zio
   * @param trace
   * @return
   */
  def autoinstrumented[R, E, A](zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]

  /**
   * Use when you need to propagate the context manually from a downstream service to an upstream service.
   *
   * See for example:
   * [[https://github.com/zio/zio-telemetry/blob/v4.0.0-rc/opentelemetry-example/src/main/scala/zio/telemetry/opentelemetry/example/http/ProxyHttpApp.scala]]
   *
   * {{{
   *   val carrier = OutgoingContextCarrier.default()
   *
   *   tracer.root("upstream-endpoint") { span =>
   *     for {
   *        // Set some context data
   *        _ <- span.setAttribute("key", "value")
   *        _ <- openTelemetry.baggage.set("key1", "value1")
   *
   *        // Mutate the carrier's kernel with the context data available at this point
   *        _ <- openTelemetry.propagate(carrier)
   *
   *        // Call the upstream service using the modified carrier's kernel
   *        _ <- upstream.call(carrier.kernel.toMap)
   *     } yield ()
   *   }
   * }}}
   *
   * @param carrier
   * @param trace
   * @return
   */
  def propagate[C](carrier: OutgoingContextCarrier[C])(implicit trace: Trace): UIO[Unit]

  /**
   * Use when you need to receive the context manually passed in by an upstream service.
   *
   * See for example:
   * [[https://github.com/zio/zio-telemetry/blob/v4.0.0-rc/opentelemetry-example/src/main/scala/zio/telemetry/opentelemetry/example/http/BackendHttpApp.scala]]
   *
   * {{{
   *   def headersCarrier(initial: Headers): IncomingContextCarrier[Headers] =
   *    new IncomingContextCarrier[Headers] {
   *      override val kernel: Headers = initial
   *
   *      override def getAllKeys(carrier: Headers): Iterable[String] =
   *        carrier.headers.map(_.headerName)
   *
   *      override def getByKey(carrier: Headers, key: String): Option[String] =
   *        carrier.headers.get(key)
   *
   *    }
   *
   *  // Create a carrier from the incoming request headers
   *  val carrier = headersCarrier(request.headers)
   *
   *  // Use the carrier to restore the context data
   *  openTelemetry.continue(carrier) {
   *    tracer.span("downstream-endpoint") { span =>
   *      for {
   *       // Get the baggage data from the incoming context carrier
   *       value1 <- openTelemetry.baggage.get("key1")
   *      } yield ()
   *    }
   *  }
   * }}}
   *
   * @param carrier
   * @param zio
   * @param trace
   * @return
   */
  def continue[R, E, A, C](
    carrier: IncomingContextCarrier[C]
  )(zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]

  /**
   * Use when you need to pass contextual information between spans.
   */
  val baggage: Baggage

  // TODO: get rid of it, it is a part of implementation needed for developers only
  private[opentelemetry] val ctxStorage: ContextStorage

  trait UnsafeAPI {
    def getCurrentContext(implicit trace: Trace): UIO[Context]

    def asJava: JOpenTelemetry
  }

  val unsafe: UnsafeAPI

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
 * The entrypoint to telemetry functionality for tracer, metrics, logger and baggage.
 */
object OpenTelemetry {

  final class OpenTelemetrySdk private[opentelemetry] (
    val ctxStorage: ContextStorage,
    underlying: JOpenTelemetry,
    ctxPropagator: ContextPropagator = ContextPropagator.default,
    logAnnotated: Boolean = false
  ) extends OpenTelemetry {

    override def autoinstrumented[R, E, A](zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
      ctxStorage.locally(Context.current())(zio)

    override def propagate[C](carrier: OutgoingContextCarrier[C])(implicit trace: Trace): UIO[Unit] =
      ctxStorage.get.map(ctxPropagator.instance.inject(_, carrier.kernel, carrier)).unit

    override def continue[R, E, A, C](
      carrier: IncomingContextCarrier[C]
    )(zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
      ctxStorage.locally(ctxPropagator.instance.extract(Context.root, carrier.kernel, carrier))(zio)

    override val baggage: Baggage =
      Baggage.make(ctxStorage, logAnnotated)

    override val unsafe: UnsafeAPI =
      new UnsafeAPI {
        def getCurrentContext(implicit trace: Trace): UIO[Context] =
          ctxStorage.get

        def asJava: JOpenTelemetry =
          underlying
      }

  }

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
      } yield new OpenTelemetrySdk(ContextStorage.JavaOtelThreadLocal, underlying, propagator, logAnnotated)
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
      } yield new OpenTelemetrySdk(ctxStorage, underlying, ctxPropagator, logAnnotated)
    }

  def custom(zio: => ZIO[Scope, Throwable, JOpenTelemetry])(implicit trace: Trace): TaskLayer[OpenTelemetry] =
    custom()(zio)

  def noop(logAnnotated: Boolean = false)(implicit trace: Trace): TaskLayer[OpenTelemetry] =
    ZLayer.scoped {
      for {
        underlying <- ZIO.attempt(JOpenTelemetry.noop())
        ctxStorage <- ContextStorage.zioFiberRefScoped
      } yield new OpenTelemetrySdk(ctxStorage, underlying, ContextPropagator.noop, logAnnotated)
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
  def logger(
    instrumentationScopeName: String,
    logLevel: LogLevel = LogLevel.Info
  )(implicit trace: Trace): URLayer[OpenTelemetry, Unit] =
    ZLayer.scoped {
      for {
        openTelemetry <- ZIO.service[OpenTelemetry]
        loggerProvider = openTelemetry.unsafe.asJava.getLogsBridge
        _             <- Logger.make(loggerProvider, openTelemetry.ctxStorage, instrumentationScopeName, logLevel)
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
