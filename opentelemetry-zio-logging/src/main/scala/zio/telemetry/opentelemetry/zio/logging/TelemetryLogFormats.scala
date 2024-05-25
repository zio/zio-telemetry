package zio.telemetry.opentelemetry.zio.logging

import io.opentelemetry.api.trace.{Span, SpanContext}
import io.opentelemetry.context.Context
import zio.FiberRefs
import zio.logging.LogFormat
import zio.telemetry.opentelemetry.context.ContextStorage

object TelemetryLogFormats {

  /**
   * Will print traceId from current span or nothing when not in span
   */
  def traceId(contextStorage: ContextStorage): LogFormat = LogFormat.make { (builder, _, _, _, _, _, fiberRefs, _, _) =>
    getSpanContext(contextStorage, fiberRefs).map(_.getTraceId) match {
      case Some(traceId) => builder.appendText(traceId)
      case None          => ()
    }
  }

  /**
   * Will print spanId from current span or nothing when not in span
   */
  def spanId(contextStorage: ContextStorage): LogFormat = LogFormat.make { (builder, _, _, _, _, _, fiberRefs, _, _) =>
    getSpanContext(contextStorage, fiberRefs).map(_.getSpanId) match {
      case Some(spanId) => builder.appendText(spanId)
      case None         => ()
    }
  }

  private def getSpanContext(ctxStorage: ContextStorage, fiberRefs: FiberRefs): Option[SpanContext] =
    (ctxStorage match {
      case ref: ContextStorage.ZIOFiberRef => fiberRefs.get(ref.ref)
      case ContextStorage.Native           => Some(Context.current())
    })
      .map(Span.fromContext)
      .map(_.getSpanContext)

}
