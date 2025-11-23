package zio.telemetry.opentelemetry.testkit

import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.trace.SdkTracerProvider
import zio._
import zio.telemetry.opentelemetry.core.OpenTelemetry
import zio.telemetry.opentelemetry.core.context.internal.ContextStorage

object OpenTelemetryTestkit {

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
      } yield OpenTelemetry.make(ctxStorage, underlying)
    }
  }

  def ctxStorageZioFiberRef: ULayer[ContextStorage] =
    ZLayer.scoped(ContextStorage.zioFiberRefScoped)

  def ctxStorageJavaOtelThreadLocal: ULayer[ContextStorage] =
    ZLayer.succeed(ContextStorage.JavaOtelThreadLocal)

}
