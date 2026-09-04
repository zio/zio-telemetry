package zio.telemetry.opentelemetry.core.logs

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.logs.{Logger => JLogger, LoggerProvider, Severity}
import io.opentelemetry.context.Context
import zio._
import zio.telemetry.opentelemetry.core.context.internal.ContextStorage

private[opentelemetry] object Logger {

  def install(
    loggerProvider: LoggerProvider,
    ctxStorage: ContextStorage,
    instrumentationScopeName: String,
    logLevel: LogLevel = LogLevel.Info
  ): URIO[Scope, Unit] =
    for {
      logger <- ZIO.succeed(
                  zioLogger(instrumentationScopeName)(ctxStorage, loggerProvider)
                    .filterLogLevel(_ >= logLevel)
                )
      _      <- ZIO.withLoggerScoped(logger)
    } yield ()

  private[opentelemetry] def zioLogger(instrumentationScopeName: String)(
    ctxStorage: ContextStorage,
    loggerProvider: LoggerProvider
  ): ZLogger[String, Unit] =
    new ZLogger[String, Unit] {

      val logger: JLogger = loggerProvider.get(instrumentationScopeName)

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
        if (!cause.isEmpty) {
          val _ = builder.setException(causeToThrowable(cause))
        }

        ctxStorage match {
          case cs: ContextStorage.ZIOFiberRef             =>
            context.get(cs.ref).foreach(builder.setContext)
          case _: ContextStorage.JavaOtelThreadLocal.type =>
            builder.setContext(Context.current())
        }

        builder.emit()
      }

      private def causeToThrowable(cause: Cause[Any]): Throwable =
        (cause.failures, cause.defects, cause.isInterrupted) match {
          case ((failure: Throwable) :: Nil, Nil, false) => failure
          case (Nil, defect :: Nil, false)               => defect
          case _                                         => FiberFailure(cause)
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
