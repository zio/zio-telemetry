package zio.telemetry.opentelemetry.example.otel

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.LoggerProvider
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.`export`.SimpleLogRecordProcessor
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.semconv.ResourceAttributes
import zio._

/**
 * https://datalust.co/seq
 */
object SeqLoggerProvider {

  def live(resourceName: String): TaskLayer[LoggerProvider] =
    ZLayer(
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
    )

}
