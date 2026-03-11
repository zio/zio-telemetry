package zio.telemetry.opentelemetry.core

import io.opentelemetry.api.{OpenTelemetry => JOpenTelemetry}
import io.opentelemetry.context.Context
import zio._
import zio.telemetry.opentelemetry.core.baggage.Baggage
import zio.telemetry.opentelemetry.core.context.internal.ContextStorage
import zio.telemetry.opentelemetry.core.context.{ContextPropagator, IncomingContextCarrier, OutgoingContextCarrier}
import zio.telemetry.opentelemetry.core.trace.LogSpanner

trait OpenTelemetry { self =>

  /**
   * Use it exclusively together with
   * [[https://github.com/open-telemetry/opentelemetry-java-instrumentation OTEL Java Auto Instrumentation]]
   *
   * When your application is instrumented with the OTEL Java Agent, it automatically performs end-to-end context
   * propagation for any supported libraries you use. Manual instrumentation is also supported. Must be used only once
   * per entry point of your application.
   *
   * See for example:
   * [[https://github.com/zio/zio-telemetry/blob/v4.0.0-rc/opentelemetry-instrumentation-example/src/main/scala/zio/telemetry/opentelemetry/instrumentation/example/http/BackendHttpApp.scala]]
   *
   * {{{
   *  openTelemetry.autoinstrumented(
   *    zio @ tracer.aspects.span("internal")
   *  )
   * }}}
   *
   * @param zio
   * @param trace
   * @return
   */
  def autoinstrumented[R, E, A](zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]

  /**
   * Use when you need to propagate the context manually from a downstream service to an upstream service.
   *
   * See for example:
   * [[https://github.com/zio/zio-telemetry/blob/v4.0.0-rc/opentelemetry-example/src/main/scala/zio/telemetry/opentelemetry/example/http/ProxyHttpApp.scala]]
   *
   * {{{
   *   val carrier = OutgoingContextCarrier.default()
   *
   *   tracer.root("upstream-endpoint") { span =>
   *     for {
   *        // Set some context data
   *        _ <- span.setAttribute("key", "value")
   *        _ <- openTelemetry.baggage.set("key1", "value1")
   *
   *        // Mutate the carrier's kernel with the context data available at this point
   *        _ <- openTelemetry.propagate(carrier)
   *
   *        // Call the upstream service using the modified carrier's kernel
   *        _ <- upstream.call(carrier.kernel.toMap)
   *     } yield ()
   *   }
   * }}}
   *
   * @param carrier
   * @param trace
   * @return
   */
  def propagate[C](carrier: OutgoingContextCarrier[C])(implicit trace: Trace): UIO[Unit]

  /**
   * Use when you need to receive the context manually passed in by an upstream service.
   *
   * See for example:
   * [[https://github.com/zio/zio-telemetry/blob/v4.0.0-rc/opentelemetry-example/src/main/scala/zio/telemetry/opentelemetry/example/http/BackendHttpApp.scala]]
   *
   * {{{
   *   def headersCarrier(initial: Headers): IncomingContextCarrier[Headers] =
   *    new IncomingContextCarrier[Headers] {
   *      override val kernel: Headers = initial
   *
   *      override def getAllKeys(carrier: Headers): Iterable[String] =
   *        carrier.headers.map(_.headerName)
   *
   *      override def getByKey(carrier: Headers, key: String): Option[String] =
   *        carrier.headers.get(key)
   *
   *    }
   *
   *  // Create a carrier from the incoming request headers
   *  val carrier = headersCarrier(request.headers)
   *
   *  // Use the carrier to restore the context data
   *  openTelemetry.continue(carrier) {
   *    tracer.span("downstream-endpoint") { span =>
   *      for {
   *       // Get the baggage data from the incoming context carrier
   *       value1 <- openTelemetry.baggage.get("key1")
   *      } yield ()
   *    }
   *  }
   * }}}
   *
   * @param carrier
   * @param zio
   * @param trace
   * @return
   */
  def continue[R, E, A, C](
    carrier: IncomingContextCarrier[C]
  )(zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]

  /**
   * Wraps an effect with a named span using the currently installed `LogSpanner`.
   *
   * By default delegates to `ZIO.logSpan`. When an OTEL backend is installed, creates real OTEL spans.
   *
   * @param name
   *   the span name
   * @param zio
   *   the effect to wrap
   */
  def logSpan[R, E, A](name: String)(zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    LogSpanner.currentLogSpanner.getWith(_.logSpan(name)(zio))

  /**
   * Use when you need to pass contextual information between spans.
   */
  val baggage: Baggage

  trait UnsafeAPI {
    def getCurrentContext(implicit trace: Trace): UIO[Context]

    def getCtxStorage: ContextStorage

    def asJava: JOpenTelemetry
  }

  val unsafe: UnsafeAPI

  object aspects {

    def autoinstrumented: ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
      new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
        override def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          self.autoinstrumented(zio)
      }

    def continue[C](carrier: IncomingContextCarrier[C]): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
      new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
        override def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          self.continue(carrier)(zio)
      }

    def logSpan(name: String): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
      new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
        override def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          self.logSpan(name)(zio)
      }

  }

}

private[opentelemetry] object OpenTelemetry {

  def make(
    ctxStorage: ContextStorage,
    underlying: JOpenTelemetry,
    ctxPropagator: ContextPropagator = ContextPropagator.default,
    logAnnotated: Boolean = false
  ): OpenTelemetry = new OpenTelemetry {

    override def autoinstrumented[R, E, A](zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
      ctxStorage.locally(Context.current())(zio)

    override def propagate[C](carrier: OutgoingContextCarrier[C])(implicit trace: Trace): UIO[Unit] =
      ctxStorage.get.map(ctxPropagator.instance.inject(_, carrier.kernel, carrier)).unit

    override def continue[R, E, A, C](
      carrier: IncomingContextCarrier[C]
    )(zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
      ctxStorage.locally(ctxPropagator.instance.extract(Context.root, carrier.kernel, carrier))(zio)

    override val baggage: Baggage =
      Baggage.make(ctxStorage, logAnnotated)

    override val unsafe: UnsafeAPI =
      new UnsafeAPI {

        override def getCtxStorage: ContextStorage =
          ctxStorage

        def getCurrentContext(implicit trace: Trace): UIO[Context] =
          ctxStorage.get

        def asJava: JOpenTelemetry =
          underlying
      }

  }

}
