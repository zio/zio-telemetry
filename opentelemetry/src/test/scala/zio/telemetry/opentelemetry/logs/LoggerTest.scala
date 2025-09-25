package zio.telemetry.opentelemetry.logs

import zio._
import zio.test.Assertion._
import zio.test._

import scala.jdk.CollectionConverters._
import zio.telemetry.opentelemetry.testkit.OpenTelemetryTestkit
import zio.telemetry.opentelemetry.testkit.logs.LoggerTestkit
import zio.telemetry.opentelemetry.testkit.trace.TracerTestkit
import io.opentelemetry.api.logs.Severity

object LoggerTest extends ZIOSpecDefault {

  val instrumentationScopeName = "LoggerTest"

  override def spec: Spec[TestEnvironment with Scope, Any] =
    suite("zio opentelemetry")(
      suite("Logger")(
        test("without tracer context") {
          for {
            loggerTestkit <- ZIO.service[LoggerTestkit]
            _             <- ZIO.logAnnotate("zio", "logger")(ZIO.logInfo("test"))
            logRecords    <- loggerTestkit.getFinishedLogRecords
          } yield {
            val r                        = logRecords.head
            val body                     = r.getBodyValue.asString()
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
            assert(instrumentationScopeName)(equalTo("without tracer context")) &&
            assert(attributes)(equalTo(Map("zio" -> "logger"))) &&
            assert(traceId)(equalTo("00000000000000000000000000000000")) &&
            assert(spanId)(equalTo("0000000000000000"))
          }
        }.provide(LoggerTestkit.inMemory("without tracer context"), OpenTelemetryTestkit.ctxStorageZioFiberRef),
        test("filter log level") {
          for {
            loggerTestkit <- ZIO.service[LoggerTestkit]
            _             <- ZIO.logInfo("test")
            _             <- ZIO.logWarning("test")
            logRecords    <- loggerTestkit.getFinishedLogRecords
          } yield {
            val r                        = logRecords.head
            val body                     = r.getBodyValue.asString()
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
        }.provide(
          LoggerTestkit.inMemory("filter log level", LogLevel.Warning),
          OpenTelemetryTestkit.ctxStorageZioFiberRef
        ),
        test("multiple loggers") {
          def logRecordsZIO(message: String) =
            for {
              loggerTestkit <- ZIO.service[LoggerTestkit]
              _             <- ZIO.logInfo(message)
              logRecords    <- loggerTestkit.getFinishedLogRecords
            } yield logRecords

          for {
            logRecords1 <- logRecordsZIO("test1").provideLayer(LoggerTestkit.inMemory("test1"))
            logRecords2 <- logRecordsZIO("test2").provideLayer(LoggerTestkit.inMemory("test2"))
          } yield {
            val r1 = logRecords1.head
            val r2 = logRecords2.head

            assert(r1.getInstrumentationScopeInfo.getName)(equalTo("test1")) &&
            assert(r2.getInstrumentationScopeInfo.getName)(equalTo("test2"))
          }
        }.provide(OpenTelemetryTestkit.ctxStorageZioFiberRef),
        test("tracer context (fiberRef)") {
          for {
            tracerTestkit <- ZIO.service[TracerTestkit]
            tracer        <- tracerTestkit.getTracer(instrumentationScopeName)
            result        <- tracer.root("ROOT") { span =>
                               for {
                                 loggerTestkit <- ZIO.service[LoggerTestkit]
                                 _             <- ZIO.logInfo("test")
                                 logRecords    <- loggerTestkit.getFinishedLogRecords
                               } yield {
                                 val r                        = logRecords.head
                                 val body                     = r.getBodyValue.asString()
                                 val severityNumber           = r.getSeverity.getSeverityNumber
                                 val severityText             = r.getSeverityText
                                 val instrumentationScopeName = r.getInstrumentationScopeInfo.getName
                                 val attributes               =
                                   r.getAttributes.asMap().asScala.toMap.map { case (k, v) => k.getKey -> v.toString }
                                 val traceId                  = r.getSpanContext.getTraceId
                                 val spanId                   = r.getSpanContext.getSpanId

                                 assert(logRecords.length)(equalTo(1)) &&
                                 assert(body)(equalTo("test")) &&
                                 assert(severityNumber)(equalTo(Severity.INFO.getSeverityNumber)) &&
                                 assert(severityText)(equalTo("INFO")) &&
                                 assert(instrumentationScopeName)(equalTo("tracer context (fiberRef)")) &&
                                 assert(attributes)(equalTo(Map.empty[String, String])) &&
                                 assert(traceId)(equalTo(span.context.getTraceId)) &&
                                 assert(spanId)(equalTo(span.context.getSpanId))
                               }
                             }
          } yield result
        }.provide(
          LoggerTestkit.inMemory("tracer context (fiberRef)"),
          TracerTestkit.inMemory,
          OpenTelemetryTestkit.ctxStorageZioFiberRef
        ),
        test("tracer context (openTelemtryContext)") {
          for {
            tracerTestkit <- ZIO.service[TracerTestkit]
            tracer        <- tracerTestkit.getTracer(instrumentationScopeName)
            result        <- tracer.root("ROOT") { span =>
                               for {
                                 loggerTestkit <- ZIO.service[LoggerTestkit]
                                 _             <- ZIO.logInfo("test")
                                 logRecords    <- loggerTestkit.getFinishedLogRecords
                               } yield {
                                 val r                        = logRecords.head
                                 val body                     = r.getBodyValue.asString()
                                 val severityNumber           = r.getSeverity.getSeverityNumber
                                 val severityText             = r.getSeverityText
                                 val instrumentationScopeName = r.getInstrumentationScopeInfo.getName
                                 val attributes               =
                                   r.getAttributes.asMap().asScala.toMap.map { case (k, v) => k.getKey -> v.toString }
                                 val traceId                  = r.getSpanContext.getTraceId
                                 val spanId                   = r.getSpanContext.getSpanId

                                 assert(logRecords.length)(equalTo(1)) &&
                                 assert(body)(equalTo("test")) &&
                                 assert(severityNumber)(equalTo(Severity.INFO.getSeverityNumber)) &&
                                 assert(severityText)(equalTo("INFO")) &&
                                 assert(instrumentationScopeName)(equalTo("tracer context (openTelemtryContext)")) &&
                                 assert(attributes)(equalTo(Map.empty[String, String])) &&
                                 assert(traceId)(equalTo(span.context.getTraceId)) &&
                                 assert(spanId)(equalTo(span.context.getSpanId))
                               }
                             }
          } yield result
        }.provide(
          LoggerTestkit.inMemory("tracer context (openTelemtryContext)"),
          TracerTestkit.inMemory,
          OpenTelemetryTestkit.ctxStorageZioFiberRef
        )
      )
    )

}
