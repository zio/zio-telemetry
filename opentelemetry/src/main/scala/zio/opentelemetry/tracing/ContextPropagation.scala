package zio.opentelemetry.tracing

import io.grpc.Context
import io.opentelemetry.context.propagation.HttpTextFormat
import io.opentelemetry.trace.{ Span, TracingContextUtils }
import zio.{ UIO, URIO, ZIO }

private[opentelemetry] object ContextPropagation {
  //  The OpenTelemetry Java API forces us to deal with `Context` when extracting and injecting Spans.
  //  The context is normally retrieved through `Context.current()`.
  //  This creates and uses a ThreadLocalStorage to store the span and poses a complication,
  //  as in the zio landscape we are using FiberRefs to do that instead.
  //
  //  A solution is brought by the following observation:
  //  When extracting a span from a carrier, the context is merely a 'medium' used to retrieve the span.
  //  This means that OpenTelemetry's function 'extract':
  //  1) does the actual extraction of the span from the carrier without needing any context
  //  2) puts the extracted span into the context that was passed to it, and returns that context to the caller
  //
  //  When injecting a span into a carrier, the context also serves only as a 'medium' to retrieve the span.
  //  This means that OpenTelemetry's function 'inject':
  //  1) retrieves the span from the context that was passed to it.
  //  2) then does the actual injection of the retrieved span into the carrier, without needing any context
  //
  //  Thus we can use a 'dummy' context to play the role of medium: `Context.Root`, which doesn't have any thread local storage.
  //
  // See https://github.com/open-telemetry/opentelemetry-java/issues/575, and
  // https://github.com/open-telemetry/opentelemetry-java/issues/1104

  /**
   * Extract and returns the span from carrier `C`.
   */
  def extractSpan[C](
    httpTextFormat: HttpTextFormat,
    carrier: C,
    getter: HttpTextFormat.Getter[C]
  ): UIO[Span] =
    ZIO.uninterruptible {
      UIO(httpTextFormat.extract(Context.ROOT, carrier, getter)).map(TracingContextUtils.getSpan)
    }

  /**
   * Injects the span into carrier `C`.
   */
  def injectSpan[C](
    span: Span,
    httpTextFormat: HttpTextFormat,
    carrier: C,
    setter: HttpTextFormat.Setter[C]
  ): URIO[Tracing, Unit] =
    ZIO.uninterruptible {
      for {
        context <- UIO(TracingContextUtils.withSpan(span, Context.ROOT))
        _       <- UIO(httpTextFormat.inject(context, carrier, setter))
      } yield ()
    }

}
