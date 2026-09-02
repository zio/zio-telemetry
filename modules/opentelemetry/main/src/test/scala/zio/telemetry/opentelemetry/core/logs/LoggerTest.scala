package zio.telemetry.opentelemetry.core.logs

import io.opentelemetry.api.logs.Severity
import zio._
import zio.telemetry.opentelemetry.testkit.OpenTelemetryTestkit
import zio.telemetry.opentelemetry.testkit.logs.LoggerTestkit
import zio.telemetry.opentelemetry.testkit.trace.TracerTestkit
import zio.test.Assertion._
import zio.test._

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
            record         = logRecords.head
          } yield assert(logRecords.length)(equalTo(1)) &&
            assert(record.body)(equalTo("test")) &&
            assert(record.severity.getSeverityNumber)(equalTo(Severity.INFO.getSeverityNumber)) &&
            assert(record.severityText)(equalTo("INFO")) &&
            assert(record.instrumentationScopeInfo.name)(equalTo("without tracer context")) &&
            assert(record.attributes.asMap)(equalTo(Map("zio" -> "logger"))) &&
            assert(record.spanContext.traceId)(equalTo("00000000000000000000000000000000")) &&
            assert(record.spanContext.spanId)(equalTo("0000000000000000"))
        }.provide(LoggerTestkit.inMemory("without tracer context"), OpenTelemetryTestkit.ctxStorageZioFiberRef),
        test("filter log level") {
          for {
            loggerTestkit <- ZIO.service[LoggerTestkit]
            _             <- ZIO.logInfo("test")
            _             <- ZIO.logWarning("test")
            logRecords    <- loggerTestkit.getFinishedLogRecords
            record         = logRecords.head
          } yield assert(logRecords.length)(equalTo(1)) &&
            assert(record.body)(equalTo("test")) &&
            assert(record.severity.getSeverityNumber)(equalTo(Severity.WARN.getSeverityNumber)) &&
            assert(record.severityText)(equalTo("WARN")) &&
            assert(record.instrumentationScopeInfo.name)(equalTo("filter log level")) &&
            assert(record.attributes.isEmpty)(equalTo(true)) &&
            assert(record.spanContext.traceId)(equalTo("00000000000000000000000000000000")) &&
            assert(record.spanContext.spanId)(equalTo("0000000000000000"))
        }.provide(
          LoggerTestkit.inMemory("filter log level", LogLevel.Warning),
          OpenTelemetryTestkit.ctxStorageZioFiberRef
        ),
        test("exception cause") {
          val exception = new IllegalArgumentException("boom")

          for {
            loggerTestkit <- ZIO.service[LoggerTestkit]
            _             <- ZIO.logErrorCause("test", Cause.fail(exception))
            logRecords    <- loggerTestkit.getFinishedLogRecords
            record         = logRecords.head
            attributes     = record.attributes.asMap
          } yield assert(logRecords.length)(equalTo(1)) &&
            assert(attributes.get("exception.type"))(isSome(equalTo("java.lang.IllegalArgumentException"))) &&
            assert(attributes.get("exception.message"))(isSome(equalTo("boom"))) &&
            assert(attributes.get("exception.stacktrace"))(isSome(containsString("IllegalArgumentException: boom")))
        }.provide(LoggerTestkit.inMemory("exception cause"), OpenTelemetryTestkit.ctxStorageZioFiberRef),
        test("defect cause") {
          val exception = new IllegalStateException("boom")

          for {
            loggerTestkit <- ZIO.service[LoggerTestkit]
            _             <- ZIO.logErrorCause("test", Cause.die(exception))
            logRecords    <- loggerTestkit.getFinishedLogRecords
            attributes     = logRecords.head.attributes.asMap
          } yield assert(attributes.get("exception.type"))(isSome(equalTo("java.lang.IllegalStateException"))) &&
            assert(attributes.get("exception.message"))(isSome(equalTo("boom"))) &&
            assert(attributes.get("exception.stacktrace"))(isSome(containsString("IllegalStateException: boom")))
        }.provide(LoggerTestkit.inMemory("defect cause"), OpenTelemetryTestkit.ctxStorageZioFiberRef),
        test("composite cause") {
          val first  = new IllegalArgumentException("first")
          val second = new IllegalStateException("second")

          for {
            loggerTestkit <- ZIO.service[LoggerTestkit]
            _             <- ZIO.logErrorCause("test", Cause.fail(first) && Cause.fail(second))
            logRecords    <- loggerTestkit.getFinishedLogRecords
            attributes     = logRecords.head.attributes.asMap
          } yield assert(attributes.get("exception.type"))(isSome(equalTo("zio.FiberFailure"))) &&
            assert(attributes.get("exception.stacktrace"))(
              isSome(
                containsString("IllegalArgumentException: first") && containsString("IllegalStateException: second")
              )
            )
        }.provide(LoggerTestkit.inMemory("composite cause"), OpenTelemetryTestkit.ctxStorageZioFiberRef),
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
            record1      = logRecords1.head
            record2      = logRecords2.head
          } yield assert(record1.instrumentationScopeInfo.name)(equalTo("test1")) &&
            assert(record2.instrumentationScopeInfo.name)(equalTo("test2"))
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
                                 record         = logRecords.head
                               } yield assert(logRecords.length)(equalTo(1)) &&
                                 assert(record.body)(equalTo("test")) &&
                                 assert(record.severity.getSeverityNumber)(equalTo(Severity.INFO.getSeverityNumber)) &&
                                 assert(record.severityText)(equalTo("INFO")) &&
                                 assert(record.instrumentationScopeInfo.name)(equalTo("tracer context (fiberRef)")) &&
                                 assert(record.attributes.isEmpty)(equalTo(true)) &&
                                 assert(record.spanContext.traceId)(equalTo(span.context.getTraceId)) &&
                                 assert(record.spanContext.spanId)(equalTo(span.context.getSpanId))
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
                                 record         = logRecords.head
                               } yield assert(logRecords.length)(equalTo(1)) &&
                                 assert(record.body)(equalTo("test")) &&
                                 assert(record.severity.getSeverityNumber)(equalTo(Severity.INFO.getSeverityNumber)) &&
                                 assert(record.severityText)(equalTo("INFO")) &&
                                 assert(record.instrumentationScopeInfo.name)(
                                   equalTo("tracer context (openTelemtryContext)")
                                 ) &&
                                 assert(record.attributes.isEmpty)(equalTo(true)) &&
                                 assert(record.spanContext.traceId)(equalTo(span.context.getTraceId)) &&
                                 assert(record.spanContext.spanId)(equalTo(span.context.getSpanId))
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
