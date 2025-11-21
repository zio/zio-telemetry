package zio.telemetry.opentelemetry.testkit.logs

import zio._
import zio.telemetry.opentelemetry.context.internal.ContextStorage
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import scala.jdk.CollectionConverters._
import zio.telemetry.opentelemetry.logs.Logger

trait LoggerTestkit {

  def getFinishedLogRecords: UIO[List[LogRecordData]]

}

object LoggerTestkit {

  def inMemory(
    instrumentationScopeName: String,
    logLevel: LogLevel = LogLevel.Info
  )(implicit trace: Trace): RLayer[ContextStorage, LoggerTestkit] = {
    val logRecordExporterLayer =
      ZLayer(ZIO.attempt(InMemoryLogRecordExporter.create()))
    val loggerProviderLayer    =
      ZLayer {
        for {
          logRecordExporter  <- ZIO.service[InMemoryLogRecordExporter]
          logRecordProcessor <- ZIO.attempt(SimpleLogRecordProcessor.create(logRecordExporter))
          loggerProvider     <- ZIO.attempt(SdkLoggerProvider.builder().addLogRecordProcessor(logRecordProcessor).build())
        } yield loggerProvider
      }
    val testkitLayer           =
      ZLayer {
        for {
          logRecordExporter <- ZIO.service[InMemoryLogRecordExporter]
        } yield new LoggerTestkit {

          override def getFinishedLogRecords: UIO[List[LogRecordData]] =
            ZIO.succeed(logRecordExporter.getFinishedLogRecordItems().asScala.toList.map(LogRecordData(_)))

        }
      }
    val installLoggerLayer     = ZLayer.scoped {
      for {
        ctxStorage     <- ZIO.service[ContextStorage]
        loggerProvider <- ZIO.service[SdkLoggerProvider]
        _              <- Logger.install(loggerProvider, ctxStorage, instrumentationScopeName, logLevel)
      } yield ()
    }

    Runtime.removeDefaultLoggers >>>
      (logRecordExporterLayer >>> loggerProviderLayer) >>>
      installLoggerLayer >>>
      logRecordExporterLayer >>>
      testkitLayer
  }

}
