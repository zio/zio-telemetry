//> using scala "3.5.2"
//> using dep dev.zio::zio:2.1.13
//> using dep dev.zio::zio-opentelemetry:3.0.0
//> using dep io.opentelemetry:opentelemetry-sdk:1.44.1
//> using dep io.opentelemetry:opentelemetry-sdk-trace:1.44.1
//> using dep io.opentelemetry:opentelemetry-exporter-logging-otlp:1.44.1
//> using dep io.opentelemetry.semconv:opentelemetry-semconv:1.22.0-alpha

import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.`export`.PeriodicMetricReader
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.api.common
import io.opentelemetry.semconv.ResourceAttributes
import io.opentelemetry.exporter.logging.otlp.OtlpJsonLoggingSpanExporter
import io.opentelemetry.exporter.logging.otlp.OtlpJsonLoggingMetricExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.api
import zio.*
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.metrics.Meter
import zio.telemetry.opentelemetry.common.Attributes
import zio.telemetry.opentelemetry.common.Attribute
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.context.ContextStorage

object MetricsApp extends ZIOAppDefault {

  val instrumentationScopeName = "dev.zio.MetricsApp"
  val resourceName             = "metrics-app"

  // Prints to stdout in OTLP Json format
  val stdoutMeterProvider: RIO[Scope, SdkMeterProvider] =
    for {
      metricExporter <- ZIO.fromAutoCloseable(ZIO.succeed(OtlpJsonLoggingMetricExporter.create()))
      metricReader   <-
        ZIO.fromAutoCloseable(ZIO.succeed(PeriodicMetricReader.builder(metricExporter).setInterval(5.second).build()))
      meterProvider  <-
        ZIO.fromAutoCloseable(
          ZIO.succeed(
            SdkMeterProvider
              .builder()
              .registerMetricReader(metricReader)
              .setResource(Resource.create(common.Attributes.of(ResourceAttributes.SERVICE_NAME, resourceName)))
              .build()
          )
        )
    } yield meterProvider

  // Prints to stdout in OTLP Json format
  val stdoutTracerProvider: RIO[Scope, SdkTracerProvider] =
    for {
      spanExporter   <- ZIO.fromAutoCloseable(ZIO.succeed(OtlpJsonLoggingSpanExporter.create()))
      spanProcessor  <- ZIO.fromAutoCloseable(ZIO.succeed(SimpleSpanProcessor.create(spanExporter)))
      tracerProvider <-
        ZIO.fromAutoCloseable(
          ZIO.succeed(
            SdkTracerProvider
              .builder()
              .setResource(Resource.create(common.Attributes.of(ResourceAttributes.SERVICE_NAME, resourceName)))
              .addSpanProcessor(spanProcessor)
              .build()
          )
        )
    } yield tracerProvider

  val otelSdkLayer: TaskLayer[api.OpenTelemetry] =
    OpenTelemetry.custom(
      for {
        tracerProvider <- stdoutTracerProvider
        meterProvider  <- stdoutMeterProvider
        sdk            <- ZIO.fromAutoCloseable(
                            ZIO.succeed(
                              OpenTelemetrySdk
                                .builder()
                                .setTracerProvider(tracerProvider)
                                .setMeterProvider(meterProvider)
                                .build()
                            )
                          )
      } yield sdk
    )

  // Stores the number of seconds elapsed since the application startup
  val tickRefLayer: ULayer[Ref[Long]] =
    ZLayer(
      for {
        ref <- Ref.make(0L)
        _   <- ref
                 .update(_ + 1)
                 .repeat[Any, Long](Schedule.spaced(1.second))
                 .forkDaemon
      } yield ref
    )

  // Records the number of seconds elapsed since the application startup
  val tickCounterLayer: RLayer[Meter & Ref[Long], Unit] =
    ZLayer.scoped(
      for {
        meter <- ZIO.service[Meter]
        ref   <- ZIO.service[Ref[Long]]
        // Initialize observable counter instrument
        _     <- meter.observableCounter("tick_counter") { om =>
                   for {
                     tick <- ref.get
                     _    <- om.record(tick)
                   } yield ()
                 }
      } yield ()
    )

  override def run =
    ZIO
      .serviceWithZIO[Tracing] { tracing =>
        val logic = for {
          meter                <- ZIO.service[Meter]
          // Create a counter
          messageLengthCounter <- meter.counter("message_length_counter")
          // Read user input
          message              <- Console.readLine
          // Sleep for the number of seconds equal to the message length  to demonstrate the work of observable counter
          _                    <- ZIO.sleep(message.length.seconds)
          // Record the message length
          _                    <- messageLengthCounter.add(message.length, Attributes(Attribute.string("message", message)))
        } yield message

        // By wrapping our logic into a span, we make the `messageLengthCounter` data points correlated with a "root_span" automatically.
        // Additionally we implicitly add one more attribute to the `messageLenghtCounter` as it is wrapped into a `ZIO.logAnnotate` call.
        ZIO.logAnnotate("zio", "annotation")(logic) @@ tracing.aspects.root("root_span")
      }
      .provide(
        otelSdkLayer,
        OpenTelemetry.metrics(instrumentationScopeName, logAnnotated = true),
        OpenTelemetry.tracing(instrumentationScopeName),
        OpenTelemetry.contextZIO,
        tickCounterLayer,
        tickRefLayer
      )

}
