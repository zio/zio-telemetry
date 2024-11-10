//> using scala "3.5.2"
//> using dep dev.zio::zio:2.1.12
//> using dep dev.zio::zio-opentelemetry:3.0.0
//> using dep io.opentelemetry:opentelemetry-sdk:1.44.1
//> using dep io.opentelemetry:opentelemetry-sdk-trace:1.44.1
//> using dep io.opentelemetry:opentelemetry-exporter-logging-otlp:1.44.1
//> using dep io.opentelemetry.semconv:opentelemetry-semconv:1.22.0-alpha

import io.opentelemetry.exporter.logging.otlp.OtlpJsonLoggingSpanExporter
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.semconv.ResourceAttributes
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.api
import zio.*
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.baggage.propagation.BaggagePropagator
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.context.IncomingContextCarrier
import zio.telemetry.opentelemetry.context.OutgoingContextCarrier
import scala.collection.mutable

object PropagatingApp extends ZIOAppDefault {

  val instrumentationScopeName = "dev.zio.PropagatingApp"
  val resourceName             = "propagating-app"

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
              .setResource(Resource.create(Attributes.of(ResourceAttributes.SERVICE_NAME, resourceName)))
              .addSpanProcessor(spanProcessor)
              .build()
          )
        )
    } yield tracerProvider

  val otelSdkLayer: TaskLayer[api.OpenTelemetry] =
    OpenTelemetry.custom(
      for {
        tracerProvider <- stdoutTracerProvider
        sdk            <- ZIO.fromAutoCloseable(
                            ZIO.succeed(
                              OpenTelemetrySdk
                                .builder()
                                .setTracerProvider(tracerProvider)
                                .build()
                            )
                          )
      } yield sdk
    )

  override def run =
    ZIO
      .serviceWithZIO[Tracing] { tracing =>
        val tracePropagator   = TraceContextPropagator.default
        val baggagePropagator = BaggagePropagator.default
        // Using the same kernel and carriers for baggage and tracing context propagation is safe
        // since their encodings occupy different keys in the OTEL context.
        val kernel            = mutable.Map.empty[String, String]
        val outgoingCarrier   = OutgoingContextCarrier.default(kernel)
        val incomingCarrier   = IncomingContextCarrier.default(kernel)

        ZIO.serviceWithZIO[Baggage] { baggage =>
          // Representing upstream service
          val upstreamService = for {
            // Read user input
            message <- Console.readLine
            // Set and propagate the baggage data using outgoing carrier
            _       <- baggage.set("message", message)
            _       <- baggage.inject(baggagePropagator, outgoingCarrier)
            // Emulate the computation to be wrapped in a root span
            logic    = for {
                         _ <- ZIO.logInfo(s"Message length is ${message.length}")
                         // Inject the current span using outgoing carrier
                         _ <- tracing.injectSpan(tracePropagator, outgoingCarrier)
                       } yield ()
            // Run the logic, wrapping it into a root span
            _       <- logic @@ tracing.aspects.root("upstream_root_span")
          } yield ()

          // Representing downstream service
          val downstreamService = for {
            // Extract the baggage data using incoming carrier
            _      <- baggage.extract(baggagePropagator, incomingCarrier)
            data   <- baggage.getAll
            message = data("message")
            // Emulate the logic that computes message length and sets an attribute of the current span
            logic   = for {
                        _ <- ZIO.logInfo(s"Message length is ${message.length}")
                        _ <- tracing.setAttribute("message", message)
                      } yield ()
            // Run the logic, wrapping it into a child span of the upstream root span
            _      <- logic @@ tracing.aspects.extractSpan(tracePropagator, incomingCarrier, "downstream_root_span")
          } yield ()

          // Simulate the interaction between services
          upstreamService *> downstreamService
        }

      }
      .provide(
        otelSdkLayer,
        OpenTelemetry.tracing(instrumentationScopeName),
        OpenTelemetry.baggage(),
        OpenTelemetry.contextZIO
      )

}
