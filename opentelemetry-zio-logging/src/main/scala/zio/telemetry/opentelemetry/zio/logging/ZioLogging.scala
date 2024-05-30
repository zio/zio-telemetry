package zio.telemetry.opentelemetry.zio.logging

import io.opentelemetry.api.trace.{Span, SpanContext}
import io.opentelemetry.context.Context
import zio._
import zio.logging.LogFormat
import zio.telemetry.opentelemetry.context.ContextStorage

object ZioLogging {

  def logFormats: ZLayer[ContextStorage, Nothing, LogFormats] = ZLayer {
    for {
      ctxStorage <- ZIO.service[ContextStorage]
    } yield new LogFormats {
      override def traceId: LogFormat = LogFormat.make { (builder, _, _, _, _, _, fiberRefs, _, _) =>
        getSpanContext(ctxStorage, fiberRefs).map(_.getTraceId).fold(())(builder.appendText(_))
      }

      override def spanId: LogFormat = LogFormat.make { (builder, _, _, _, _, _, fiberRefs, _, _) =>
        getSpanContext(ctxStorage, fiberRefs).map(_.getSpanId).fold(())(builder.appendText(_))
      }

      private def getSpanContext(ctxStorage: ContextStorage, fiberRefs: FiberRefs): Option[SpanContext] = {
        val maybeOtelContext = ctxStorage match {
          case ref: ContextStorage.ZIOFiberRef => fiberRefs.get(ref.ref)
          case ContextStorage.Native           => Some(Context.current())
        }

        maybeOtelContext
          .map(Span.fromContext)
          .map(_.getSpanContext)
      }
    }
  }

}
