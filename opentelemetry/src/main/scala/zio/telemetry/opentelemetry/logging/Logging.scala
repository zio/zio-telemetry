package zio.telemetry.opentelemetry.logging

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.logs.{Logger, LoggerProvider, Severity}
import io.opentelemetry.context.Context
import zio._
import zio.telemetry.opentelemetry.context.ContextStorage

/**
 * Sends ZIO log records to OpenTelemetry as log records.
 */
object Logging {

  /**
   * Creates a layer that sends ZIO log records to the OpenTelemetry logger named `instrumentationScopeName`. Only
   * records at or above `logLevel` are sent. Log annotations are sent as attributes of each log record, and the context
   * that is current when the record is logged is attached to it.
   *
   * The logger is added for the scope of the layer, next to the loggers that are already set up. It needs a
   * [[zio.telemetry.opentelemetry.context.ContextStorage]] and a `LoggerProvider`.
   *
   * @param instrumentationScopeName
   *   name uniquely identifying the instrumentation scope of the logger
   * @param logLevel
   *   the lowest severity that is sent
   */
  def live(
    instrumentationScopeName: String,
    logLevel: LogLevel = LogLevel.Info
  ): URLayer[ContextStorage & LoggerProvider, Unit] =
    ZLayer.scoped(
      for {
        loggerProvider <- ZIO.service[LoggerProvider]
        ctxStorage     <- ZIO.service[ContextStorage]
        logger         <- ZIO.succeed(
                            zioLogger(instrumentationScopeName)(ctxStorage, loggerProvider)
                              .filterLogLevel(_ >= logLevel)
                          )
        _              <- ZIO.withLoggerScoped(logger)
      } yield ()
    )

  private def zioLogger(instrumentationScopeName: String)(
    ctxStorage: ContextStorage,
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

        ctxStorage match {
          case cs: ContextStorage.ZIOFiberRef =>
            context.get(cs.ref).foreach(builder.setContext)
          case _: ContextStorage.Native.type  =>
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
