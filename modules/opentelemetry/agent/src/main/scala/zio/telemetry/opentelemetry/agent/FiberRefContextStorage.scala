package zio.telemetry.opentelemetry.agent

import io.opentelemetry.context.Context
import zio._
import zio.telemetry.opentelemetry.core.context.internal.ContextStorage

final class FiberRefContextStorage(
  private[opentelemetry] val ref: FiberRef[Context]
) extends ContextStorage {

  override def get(implicit trace: Trace): UIO[Context] =
    ref.get

  override def locally[R, E, A](ctx: Context)(zio: => ZIO[R, E, A])(implicit
    trace: Trace
  ): ZIO[R, E, A] =
    ref.locally(ctx)(zio)

  override def locallyScoped(ctx: Context)(implicit trace: Trace): ZIO[Scope, Nothing, Unit] =
    ref.locallyScoped(ctx)

  override def fiberRefOption: Option[FiberRef[Context]] =
    Some(ref)

}
