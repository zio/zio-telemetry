package zio.telemetry.opentelemetry.logs

import io.opentelemetry.api.logs.{LoggerProvider, Severity}
import io.opentelemetry.api.trace.{Tracer => JTracer}
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.`export`.SimpleLogRecordProcessor
import io.opentelemetry.sdk.testing.exporter.{InMemoryLogRecordExporter, InMemorySpanExporter}
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import zio._
import zio.telemetry.opentelemetry.context.internal.ContextStorage
import zio.telemetry.opentelemetry.trace.Tracer
import zio.test.Assertion._
import zio.test._

import scala.jdk.CollectionConverters._

object LoggerTest extends ZIOSpecDefault {

  val inMemoryTracer: UIO[(InMemorySpanExporter, JTracer)] = for {
    spanExporter   <- ZIO.succeed(InMemorySpanExporter.create())
    spanProcessor  <- ZIO.succeed(SimpleSpanProcessor.create(spanExporter))
    tracerProvider <- ZIO.succeed(SdkTracerProvider.builder().addSpanProcessor(spanProcessor).build())
    tracer          = tracerProvider.get("TracingTest")
  } yield (spanExporter, tracer)

  val inMemoryTracerLayer: ULayer[InMemorySpanExporter with JTracer] =
    ZLayer.fromZIOEnvironment(inMemoryTracer.map { case (inMemorySpanExporter, tracer) =>
      ZEnvironment(inMemorySpanExporter).add(tracer)
    })

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

  def ctxStorageLayer: ULayer[ContextStorage] =
    ZLayer.scoped(ContextStorage.zioFiberRefScoped)

  def tracerMockLayer(
    logAnnotated: Boolean = false
  ): URLayer[ContextStorage, Tracer with InMemorySpanExporter with JTracer] =
    inMemoryTracerLayer >>> (tracerLiveLayer(logAnnotated) ++ inMemoryTracerLayer)

  def tracerLiveLayer(logAnnotated: Boolean = false): URLayer[JTracer with ContextStorage, Tracer] =
    ZLayer.scoped {
      for {
        ctxStorage <- ZIO.service[ContextStorage]
        jtracer    <- ZIO.service[JTracer]
        tracer     <- zio.telemetry.opentelemetry.trace.Tracer.scoped(jtracer, ctxStorage, logAnnotated)
      } yield tracer
    }

  def loggerMockLayer(
    instrumentationScopeName: String,
    logLevel: LogLevel = LogLevel.Info
  ): URLayer[ContextStorage, InMemoryLogRecordExporter with LoggerProvider] = {
    val loggerLayer = ZLayer.scoped {
      for {
        ctxStorage     <- ZIO.service[ContextStorage]
        loggerProvider <- ZIO.service[LoggerProvider]
        _              <- Logger.make(loggerProvider, ctxStorage, instrumentationScopeName, logLevel)
      } yield ()
    }

    Runtime.removeDefaultLoggers >>>
      inMemoryLoggerProviderLayer >>>
      (loggerLayer ++ inMemoryLoggerProviderLayer)
  }

  def getFinishedLogRecords: ZIO[InMemoryLogRecordExporter, Nothing, List[LogRecordData]] =
    ZIO.service[InMemoryLogRecordExporter].map(_.getFinishedLogRecordItems.asScala.toList)

  override def spec: Spec[TestEnvironment with Scope, Any] =
    suite("zio opentelemetry")(
      suite("Logging")(
        test("without tracer context") {
          for {
            _          <- ZIO.logAnnotate("zio", "logger")(ZIO.logInfo("test"))
            logRecords <- getFinishedLogRecords
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
        }.provide(loggerMockLayer("without tracer context"), ctxStorageLayer),
        test("filter log level") {
          for {
            _          <- ZIO.logInfo("test")
            _          <- ZIO.logWarning("test")
            logRecords <- getFinishedLogRecords
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
        }.provide(loggerMockLayer("filter log level", LogLevel.Warning), ctxStorageLayer),
        test("multiple loggers") {
          for {
            logRecords1 <-
              ZIO.logInfo("test1").flatMap(_ => getFinishedLogRecords).provideLayer(loggerMockLayer("test1"))
            logRecords2 <-
              ZIO.logInfo("test2").flatMap(_ => getFinishedLogRecords).provideLayer(loggerMockLayer("test2"))
          } yield {
            val r1 = logRecords1.head
            val r2 = logRecords2.head

            assert(r1.getInstrumentationScopeInfo.getName)(equalTo("test1")) &&
            assert(r2.getInstrumentationScopeInfo.getName)(equalTo("test2"))
          }
        }.provide(ctxStorageLayer),
        test("tracer context (fiberRef)") {
          ZIO.serviceWithZIO[Tracer] { tracer =>
            tracer.root("ROOT")(
              for {
                spanCtx    <- tracer.getCurrentSpanContextUnsafe
                _          <- ZIO.logInfo("test")
                logRecords <- getFinishedLogRecords
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
                assert(instrumentationScopeName)(equalTo("tracer context (fiberRef)")) &&
                assert(attributes)(equalTo(Map.empty[String, String])) &&
                assert(traceId)(equalTo(spanCtx.getTraceId)) &&
                assert(spanId)(equalTo(spanCtx.getSpanId))
              }
            )
          }
        }.provide(
          loggerMockLayer("tracer context (fiberRef)"),
          tracerMockLayer(),
          ctxStorageLayer
        ),
        test("tracer context (openTelemtryContext)") {
          ZIO.serviceWithZIO[Tracer] { tracer =>
            tracer.root("ROOT")(
              for {
                spanCtx    <- tracer.getCurrentSpanContextUnsafe
                _          <- ZIO.logInfo("test")
                logRecords <- getFinishedLogRecords
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
                assert(instrumentationScopeName)(equalTo("tracer context (openTelemtryContext)")) &&
                assert(attributes)(equalTo(Map.empty[String, String])) &&
                assert(traceId)(equalTo(spanCtx.getTraceId)) &&
                assert(spanId)(equalTo(spanCtx.getSpanId))
              }
            )
          }
        }.provide(
          loggerMockLayer("tracer context (openTelemtryContext)"),
          tracerMockLayer(),
          ctxStorageLayer
        )
      )
    )

}
