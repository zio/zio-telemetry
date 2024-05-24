package zio.telemetry.opentelemetry.logging

import io.opentelemetry.api.trace.{Span, SpanContext}
import io.opentelemetry.context.Context
import zio.FiberRefs
import zio.telemetry.opentelemetry.context.ContextStorage

/**
 * zio-telemetry does not related directly on zio-logging so the LogFormat has to be constructed manually
 *
 * {{{
 *   import zio.logging.LogFormat
 *   import zio.telemetry.opentelemetry.context.ContextStorage
 *   import zio.telemetry.opentelemetry.logging.ZioLogging
 *
 *   def traceIdLogFormat(contextStorage: ContextStorage) = LogFormat.make { (builder, _, _, _, _, _, fiberRefs, _, _) =>
 *     builder.appendText(ZioLogging.traceId(contextStorage, fiberRefs).getOrElse("not-available"))
 *   }
 *
 *   def spanIdLogFormat(contextStorage: ContextStorage) = LogFormat.make { (builder, _, _, _, _, _, fiberRefs, _, _) =>
 *     builder.appendText(ZioLogging.spanId(contextStorage, fiberRefs).getOrElse("not-available"))
 *   }
 * }}}
 */
object ZioLogging {

  def traceId(ctxStorage: ContextStorage, fiberRefs: FiberRefs): Option[String] =
    getSpanContext(ctxStorage, fiberRefs).map(_.getTraceId)

  def spanId(ctxStorage: ContextStorage, fiberRefs: FiberRefs): Option[String] =
    getSpanContext(ctxStorage, fiberRefs).map(_.getSpanId)

  private def getSpanContext(ctxStorage: ContextStorage, fiberRefs: FiberRefs): Option[SpanContext] =
    (ctxStorage match {
      case ref: ContextStorage.ZIOFiberRef => fiberRefs.get(ref.ref)
      case ContextStorage.Native           => Some(Context.current())
    })
      .map(Span.fromContext)
      .map(_.getSpanContext)

}
