package zio.telemetry.opentelemetry.logging

import io.opentelemetry.api.logs.{LoggerProvider, Severity}
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.`export`.SimpleLogRecordProcessor
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter
import zio._
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.tracing.{Tracing, TracingTest}
import zio.test.Assertion._
import zio.test._

import scala.jdk.CollectionConverters._

object LoggingTest extends ZIOSpecDefault {

  val inMemoryLogLoggerProvider: ZIO[Any, Nothing, (InMemoryLogRecordExporter, SdkLoggerProvider)] =
    for {
      logRecordExporter  <- ZIO.succeed(InMemoryLogRecordExporter.create())
      logRecordProcessor <- ZIO.succeed(SimpleLogRecordProcessor.create(logRecordExporter))
      loggerProvider     <- ZIO.succeed(SdkLoggerProvider.builder().addLogRecordProcessor(logRecordProcessor).build())
    } yield (logRecordExporter, loggerProvider)

  val inMemoryLoggerProviderLayer: ULayer[InMemoryLogRecordExporter with LoggerProvider] =
    ZLayer.fromZIOEnvironment(inMemoryLogLoggerProvider.map { case (inMemoryLogRecordExporter, loggerProvider) =>
      ZEnvironment(inMemoryLogRecordExporter).add(loggerProvider)
    })

  def loggingMockLayer(
    instrumentationScopeName: String,
    logLevel: LogLevel = LogLevel.Info
  ): URLayer[ContextStorage, InMemoryLogRecordExporter with LoggerProvider] =
    Runtime.removeDefaultLoggers >>>
      inMemoryLoggerProviderLayer >>>
      (Logging.live(instrumentationScopeName, logLevel) ++ inMemoryLoggerProviderLayer)

  def getFinishedLogRecords: ZIO[InMemoryLogRecordExporter, Nothing, List[LogRecordData]] =
    ZIO.service[InMemoryLogRecordExporter].map(_.getFinishedLogRecordItems.asScala.toList)

  override def spec: Spec[TestEnvironment with Scope, Any] =
    suite("zio opentelemetry")(
      suite("Logging")(
        test("without tracing context") {
          for {
            _          <- ZIO.logAnnotate("zio", "logging")(ZIO.logInfo("test"))
            logRecords <- getFinishedLogRecords
          } yield {
            val r                        = logRecords.head
            val body                     = r.getBody.asString()
            val severityNumber           = r.getSeverity.getSeverityNumber
            val severityText             = r.getSeverityText
            val instrumentationScopeName = r.getInstrumentationScopeInfo.getName
            val attributes               = r.getAttributes.asMap().asScala.toMap.map { case (k, v) => k.getKey -> v.toString }
            val traceId                  = r.getSpanContext.getTraceId
            val spanId                   = r.getSpanContext.getSpanId

            assert(logRecords.length)(equalTo(1)) &&
            assert(body)(equalTo("test")) &&
            assert(severityNumber)(equalTo(Severity.INFO.getSeverityNumber)) &&
            assert(severityText)(equalTo("INFO")) &&
            assert(instrumentationScopeName)(equalTo("without tracing context")) &&
            assert(attributes)(equalTo(Map("zio" -> "logging"))) &&
            assert(traceId)(equalTo("00000000000000000000000000000000")) &&
            assert(spanId)(equalTo("0000000000000000"))
          }
        }.provide(loggingMockLayer("without tracing context"), ContextStorage.fiberRef),
        test("filter log level") {
          for {
            _          <- ZIO.logInfo("test")
            _          <- ZIO.logWarning("test")
            logRecords <- getFinishedLogRecords
          } yield {
            val r                        = logRecords.head
            val body                     = r.getBody.asString()
            val severityNumber           = r.getSeverity.getSeverityNumber
            val severityText             = r.getSeverityText
            val instrumentationScopeName = r.getInstrumentationScopeInfo.getName
            val attributes               = r.getAttributes.asMap().asScala.toMap.map { case (k, v) => k.getKey -> v.toString }
            val traceId                  = r.getSpanContext.getTraceId
            val spanId                   = r.getSpanContext.getSpanId

            assert(logRecords.length)(equalTo(1)) &&
            assert(body)(equalTo("test")) &&
            assert(severityNumber)(equalTo(Severity.WARN.getSeverityNumber)) &&
            assert(severityText)(equalTo("WARN")) &&
            assert(instrumentationScopeName)(equalTo("filter log level")) &&
            assert(attributes)(equalTo(Map.empty[String, String])) &&
            assert(traceId)(equalTo("00000000000000000000000000000000")) &&
            assert(spanId)(equalTo("0000000000000000"))
          }
        }.provide(loggingMockLayer("filter log level", LogLevel.Warning), ContextStorage.fiberRef),
        test("multiple loggers") {
          for {
            logRecords1 <-
              ZIO.logInfo("test1").flatMap(_ => getFinishedLogRecords).provideLayer(loggingMockLayer("test1"))
            logRecords2 <-
              ZIO.logInfo("test2").flatMap(_ => getFinishedLogRecords).provideLayer(loggingMockLayer("test2"))
          } yield {
            val r1 = logRecords1.head
            val r2 = logRecords2.head

            assert(r1.getInstrumentationScopeInfo.getName)(equalTo("test1")) &&
            assert(r2.getInstrumentationScopeInfo.getName)(equalTo("test2"))
          }
        }.provide(ContextStorage.fiberRef),
        test("tracing context (fiberRef)") {
          ZIO.serviceWithZIO[Tracing] { tracing =>
            tracing.root("ROOT")(
              for {
                spanCtx    <- tracing.getCurrentSpanContextUnsafe
                _          <- ZIO.logInfo("test")
                logRecords <- getFinishedLogRecords
              } yield {
                val r                        = logRecords.head
                val body                     = r.getBody.asString()
                val severityNumber           = r.getSeverity.getSeverityNumber
                val severityText             = r.getSeverityText
                val instrumentationScopeName = r.getInstrumentationScopeInfo.getName
                val attributes               = r.getAttributes.asMap().asScala.toMap.map { case (k, v) => k.getKey -> v.toString }
                val traceId                  = r.getSpanContext.getTraceId
                val spanId                   = r.getSpanContext.getSpanId

                assert(logRecords.length)(equalTo(1)) &&
                assert(body)(equalTo("test")) &&
                assert(severityNumber)(equalTo(Severity.INFO.getSeverityNumber)) &&
                assert(severityText)(equalTo("INFO")) &&
                assert(instrumentationScopeName)(equalTo("tracing context (fiberRef)")) &&
                assert(attributes)(equalTo(Map.empty[String, String])) &&
                assert(traceId)(equalTo(spanCtx.getTraceId)) &&
                assert(spanId)(equalTo(spanCtx.getSpanId))
              }
            )
          }
        }.provide(
          loggingMockLayer("tracing context (fiberRef)"),
          TracingTest.tracingMockLayer,
          ContextStorage.fiberRef
        ),
        test("tracing context (openTelemtryContext)") {
          ZIO.serviceWithZIO[Tracing] { tracing =>
            tracing.root("ROOT")(
              for {
                spanCtx    <- tracing.getCurrentSpanContextUnsafe
                _          <- ZIO.logInfo("test")
                logRecords <- getFinishedLogRecords
              } yield {
                val r                        = logRecords.head
                val body                     = r.getBody.asString()
                val severityNumber           = r.getSeverity.getSeverityNumber
                val severityText             = r.getSeverityText
                val instrumentationScopeName = r.getInstrumentationScopeInfo.getName
                val attributes               = r.getAttributes.asMap().asScala.toMap.map { case (k, v) => k.getKey -> v.toString }
                val traceId                  = r.getSpanContext.getTraceId
                val spanId                   = r.getSpanContext.getSpanId

                assert(logRecords.length)(equalTo(1)) &&
                assert(body)(equalTo("test")) &&
                assert(severityNumber)(equalTo(Severity.INFO.getSeverityNumber)) &&
                assert(severityText)(equalTo("INFO")) &&
                assert(instrumentationScopeName)(equalTo("tracing context (openTelemtryContext)")) &&
                assert(attributes)(equalTo(Map.empty[String, String])) &&
                assert(traceId)(equalTo(spanCtx.getTraceId)) &&
                assert(spanId)(equalTo(spanCtx.getSpanId))
              }
            )
          }
        }.provide(
          loggingMockLayer("tracing context (openTelemtryContext)"),
          TracingTest.tracingMockLayer,
          ContextStorage.native
        )
      )
    )

}
