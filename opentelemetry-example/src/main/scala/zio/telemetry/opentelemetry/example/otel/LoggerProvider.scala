package zio.telemetry.opentelemetry.example.otel

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.`export`.SimpleLogRecordProcessor
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.semconv.ResourceAttributes
import zio._

object LoggerProvider {

  /**
   * https://datalust.co/seq
   */
  def seq(resourceName: String): Task[SdkLoggerProvider] =
    for {
      logRecordExporter  <-
        ZIO.succeed(
          OtlpHttpLogRecordExporter
            .builder()
            .setEndpoint("http://localhost:5341/ingest/otlp/v1/logs")
            .build()
        )
      logRecordProcessor <- ZIO.succeed(SimpleLogRecordProcessor.create(logRecordExporter))
      loggerProvider     <-
        ZIO.attempt(
          SdkLoggerProvider
            .builder()
            .setResource(Resource.create(Attributes.of(ResourceAttributes.SERVICE_NAME, resourceName)))
            .addLogRecordProcessor(logRecordProcessor)
            .build()
        )
    } yield loggerProvider

  /**
   * https://fluentbit.io/
   */
  def live(resourceName: String): Task[SdkLoggerProvider] =
    for {
      logRecordExporter  <- ZIO.succeed(OtlpHttpLogRecordExporter.builder().build())
      logRecordProcessor <- ZIO.succeed(SimpleLogRecordProcessor.create(logRecordExporter))
      loggerProvider     <-
        ZIO.attempt(
          SdkLoggerProvider
            .builder()
            .setResource(Resource.create(Attributes.of(ResourceAttributes.SERVICE_NAME, resourceName)))
            .addLogRecordProcessor(logRecordProcessor)
            .build()
        )
    } yield loggerProvider

}
