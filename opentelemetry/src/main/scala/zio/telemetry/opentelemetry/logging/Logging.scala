package zio.telemetry.opentelemetry.logging

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.logs.{Logger, LoggerProvider, Severity}
import io.opentelemetry.context.Context
import zio._
import zio.telemetry.opentelemetry.context.ContextStorage

object Logging {

  def live(
    instrumentationScopeName: String,
    logLevel: LogLevel = LogLevel.Info
  ): ZLayer[ContextStorage with LoggerProvider, Nothing, Unit] =
    ZLayer
      .fromZIO(
        for {
          loggerProvider <- ZIO.service[LoggerProvider]
          contextStorage <- ZIO.service[ContextStorage]
          logger         <- ZIO.succeed(
                              zioLogger(instrumentationScopeName)(contextStorage, loggerProvider)
                                .filterLogLevel(_ >= logLevel)
                            )
        } yield logger
      )
      .flatMap(env => Runtime.addLogger(env.get))

  private def zioLogger(instrumentationScopeName: String)(
    contextStorage: ContextStorage,
    loggerProvider: LoggerProvider
  ): ZLogger[String, Unit] =
    new ZLogger[String, Unit] {

      val logger: Logger = loggerProvider.get(instrumentationScopeName)

      override def apply(
        trace: Trace,
        fiberId: FiberId,
        logLevel: LogLevel,
        message: () => String,
        cause: Cause[Any],
        context: FiberRefs,
        spans: List[LogSpan],
        annotations: Map[String, String]
      ): Unit = {
        val builder = logger.logRecordBuilder()

        builder.setBody(message())
        builder.setSeverityText(logLevel.label)
        builder.setSeverity(severityMapping(logLevel))
        annotations.foreach { case (k, v) => builder.setAttribute(AttributeKey.stringKey(k), v) }

        contextStorage match {
          case cs: ContextStorage.FiberRefContextStorage     =>
            context.get(cs.ref).foreach(builder.setContext)
          case _: ContextStorage.OpenTelemetryContextStorage =>
            builder.setContext(Context.current())
        }

        builder.emit()
      }

      private def severityMapping(level: LogLevel): Severity =
        level match {
          case LogLevel.Trace   => Severity.TRACE
          case LogLevel.Debug   => Severity.DEBUG
          case LogLevel.Info    => Severity.INFO
          case LogLevel.Warning => Severity.WARN
          case LogLevel.Error   => Severity.ERROR
          case LogLevel.Fatal   => Severity.FATAL
          case _                => Severity.UNDEFINED_SEVERITY_NUMBER
        }

    }

}
