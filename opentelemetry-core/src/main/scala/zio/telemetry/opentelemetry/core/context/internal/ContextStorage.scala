package zio.telemetry.opentelemetry.core.context.internal

import io.opentelemetry.context.Context
import zio._

sealed trait ContextStorage {

  def get(implicit trace: Trace): UIO[Context]

  def locally[R, E, A](ctx: Context)(zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]

  def locallyScoped(ctx: Context)(implicit trace: Trace): ZIO[Scope, Nothing, Unit]

}

private[opentelemetry] object ContextStorage {

  /**
   * The implementation that uses [[zio.FiberRef]] as a storage for [[io.opentelemetry.context.Context]]
   *
   * @param ref
   */
  final class ZIOFiberRef(private[opentelemetry] val ref: FiberRef[Context]) extends ContextStorage {

    override def get(implicit trace: Trace): UIO[Context] =
      ref.get

    override def locally[R, E, A](context: Context)(zio: => ZIO[R, E, A])(implicit
      trace: Trace
    ): ZIO[R, E, A] =
      ref.locally(context)(zio)

    override def locallyScoped(context: Context)(implicit trace: Trace): ZIO[Scope, Nothing, Unit] =
      ref.locallyScoped(context)

  }

  /**
   * The implementation that uses [[java.lang.ThreadLocal]] as a storage for [[io.opentelemetry.context.Context]]
   */
  object JavaOtelThreadLocal extends ContextStorage {

    override def get(implicit trace: Trace): UIO[Context] =
      ZIO.succeed(Context.current())

    override def locally[R, E, A](ctx: Context)(zio: => ZIO[R, E, A])(implicit
      trace: Trace
    ): ZIO[R, E, A] =
      ZIO.acquireReleaseWith {
        ZIO.succeed(ctx.makeCurrent())
      } { scope =>
        ZIO.succeed(scope.close())
      }(_ => zio)

    override def locallyScoped(ctx: Context)(implicit trace: Trace): ZIO[Scope, Nothing, Unit] =
      ZIO
        .acquireRelease(
          ZIO.succeed(ctx.makeCurrent())
        ) { scope =>
          ZIO.succeed(scope.close())
        }
        .unit
  }

  def zioFiberRefScoped: URIO[Scope, ContextStorage] =
    FiberRef.make[Context](Context.root()).map(new ZIOFiberRef(_))

}
