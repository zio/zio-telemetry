package zio.telemetry.opentelemetry

import io.grpc.{ ManagedChannel, ManagedChannelBuilder }
import io.opentelemetry.exporters.jaeger.JaegerGrpcSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.TracerSdk
import zio.ULayer
import zio.clock.Clock
import zio.opentelemetry.tracing.Tracing

object JaegerTracer {

  def makeService(host: String, serviceName: String): ULayer[Tracing with Clock] = {
    val tracer: TracerSdk = OpenTelemetrySdk.getTracerProvider.get("zio.telemetry.opentelemetry.JaegerTracer")

    val managedChannel: ManagedChannel = ManagedChannelBuilder.forTarget(host).usePlaintext().build()
    JaegerGrpcSpanExporter
      .newBuilder()
      .setServiceName(serviceName)
      .setChannel(managedChannel)
      .install(OpenTelemetrySdk.getTracerProvider)

    Clock.live ++ (Clock.live >>> Tracing.live(tracer))
  }
}
