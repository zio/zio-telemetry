package zio.telemetry.opentelemetry.context

import io.opentelemetry.context.Context
import zio._

/**
 * The implementation that uses [[zio.FiberRef]] as a storage for [[io.opentelemetry.context.Context]]
 *
 * @param ref
 */
final class ContextStorage(private[zio] val ref: FiberRef[Context]) {

  def get(implicit trace: Trace): UIO[Context] =
    ref.get

  def set(context: Context)(implicit trace: Trace): UIO[Unit] =
    ref.set(context)

  def getAndSet(context: Context)(implicit trace: Trace): UIO[Context] =
    ref.getAndSet(context)

  def updateAndGet(f: Context => Context)(implicit trace: Trace): UIO[Context] =
    ref.updateAndGet(f)

  def locally[R, E, A](context: Context)(zio: ZIO[R, E, A])(implicit
    trace: Trace
  ): ZIO[R, E, A] =
    ref.locally(context)(zio)

  def locallyScoped(context: Context)(implicit trace: Trace): ZIO[Scope, Nothing, Unit] =
    ref.locallyScoped(context)

}

private[opentelemetry] object ContextStorage {

  def rootScoped: URIO[Scope, ContextStorage] =
    FiberRef.make[Context](Context.root()).map(new ContextStorage(_))

}
