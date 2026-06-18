package zio.telemetry.opentelemetry.core.context.internal

import io.opentelemetry.context.Context
import zio._

private[opentelemetry] final class ContextStorage(
  private[opentelemetry] val ref: FiberRef[Context]
) {

  def get(implicit trace: Trace): UIO[Context] =
    ref.get

  def locally[R, E, A](ctx: Context)(zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    ref.locally(ctx)(zio)

  def locallyScoped(ctx: Context)(implicit trace: Trace): ZIO[Scope, Nothing, Unit] =
    ref.locallyScoped(ctx)

}

private[opentelemetry] object ContextStorage {

  def fromFiberRef(ref: FiberRef[Context]): UIO[ContextStorage] =
    ZIO.succeed(new ContextStorage(ref))

  def root: URIO[Scope, ContextStorage] =
    FiberRef.make[Context](Context.root()).map(new ContextStorage(_))

}
