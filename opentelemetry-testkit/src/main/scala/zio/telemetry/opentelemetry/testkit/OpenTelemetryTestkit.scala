package zio.telemetry.opentelemetry.testkit

import zio._
import zio.telemetry.opentelemetry.context.internal.ContextStorage
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import zio.telemetry.opentelemetry.OpenTelemetry

object OpenTelemetryTestkit {

  def ctxStorageZioFiberRef: ULayer[ContextStorage] =
    ZLayer.scoped(ContextStorage.zioFiberRefScoped)

  def ctxStorageJavaOtelThreadLocal: ULayer[ContextStorage] =
    ZLayer.succeed(ContextStorage.JavaOtelThreadLocal)

  def sdk(
    tracerProvider: Option[SdkTracerProvider] = None,
    meterProvider: Option[SdkMeterProvider] = None
  ): RLayer[ContextStorage, OpenTelemetry] = {
    val builder = OpenTelemetrySdk.builder()

    tracerProvider.foreach(builder.setTracerProvider(_))
    meterProvider.foreach(builder.setMeterProvider(_))

    ZLayer.scoped {
      for {
        ctxStorage <- ZIO.service[ContextStorage]
        underlying <- ZIO.fromAutoCloseable(ZIO.succeed(builder.build))
      } yield new OpenTelemetry.Sdk(ctxStorage, underlying)
    }
  }

}
