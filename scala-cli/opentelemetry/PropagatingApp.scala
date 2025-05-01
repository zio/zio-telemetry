//> using scala "3.6.4"
//> using dep dev.zio::zio:2.1.17
//> using dep dev.zio::zio-opentelemetry:4.0.0-RC1
//> using dep io.opentelemetry:opentelemetry-sdk:1.49.0
//> using dep io.opentelemetry:opentelemetry-sdk-trace:1.49.0
//> using dep io.opentelemetry:opentelemetry-exporter-logging-otlp:1.49.0
//> using dep io.opentelemetry.semconv:opentelemetry-semconv:1.32.0

import io.opentelemetry.exporter.logging.otlp.OtlpJsonLoggingSpanExporter
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.semconv.ServiceAttributes
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.api
import zio.*
import zio.telemetry.opentelemetry.baggage.Baggage
import zio.telemetry.opentelemetry.trace.Tracer
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.context.IncomingContextCarrier
import zio.telemetry.opentelemetry.context.OutgoingContextCarrier
import scala.collection.mutable

object PropagatingApp extends ZIOAppDefault {

  val resourceName = "propagating-app"

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
              .setResource(Resource.create(Attributes.of(ServiceAttributes.SERVICE_NAME, resourceName)))
              .addSpanProcessor(spanProcessor)
              .build()
          )
        )
    } yield tracerProvider

  def otelSdkLayer: TaskLayer[OpenTelemetry] =
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

  override def run = {
    // Representing upstream service
    val upstreamService =
      for {
        openTelemetry <- ZIO.service[OpenTelemetry]
        tracer       <- ZIO.service[Tracer]
        message       <- Console.readLine
        carrier        = OutgoingContextCarrier.default()
        // Run the logic, wrapping it into a root span
        kernel        <- (for {
                           // Emulate the computation to be wrapped in a root span
                           _ <- ZIO.logInfo(s"Message length is ${message.length}")
                           // Propagate the current span and baggage data using outgoing carrier
                           _ <- openTelemetry.propagate(carrier)
                         } yield carrier.kernel.toMap) @@
                           // Set the baggage data
                           openTelemetry.baggage.aspects.set("message", message) @@
                           tracer.aspects.root("upstream_root_span")

      } yield kernel

    // Representing downstream service
    def downstreamService(kernel: Map[String, String]) =
      for {
        openTelemetry <- ZIO.service[OpenTelemetry]
        tracer       <- ZIO.service[Tracer]
        carrier        = IncomingContextCarrier.default(mutable.Map.from(kernel))
        // Emulate the logic that computes message length and sets an attribute of the current span
        logic          = for {
                           message <- openTelemetry.baggage.get("message").map(_.getOrElse("NO MESSAGE"))
                           _       <- ZIO.logInfo(s"Message length is ${message.length}")
                           _       <- tracer.setAttribute("message", message)
                         } yield ()
        // Run the logic, wrapping it into a child span of the upstream root span
        _             <- logic @@
                           tracer.aspects.span("downstream_root_span") @@
                           // Extract the the upstream span and baggage data using incoming carrier
                           openTelemetry.aspects.continue(carrier)
      } yield ()

    // Simulate the interaction between services
    for {
      kernel <- upstreamService.provide(otelSdkLayer, OpenTelemetry.tracer("upstream.service"))
      _      <- downstreamService(kernel).provide(otelSdkLayer, OpenTelemetry.tracer("downstream.service"))
    } yield ()
  }

}
