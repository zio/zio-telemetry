package zio.telemetry.opentelemetry.tracing

import io.opentelemetry.context.Context
import zio._

private[opentelemetry] trait ContextStorage {

  def get(implicit trace: Trace): UIO[Context]

  def set(context: Context)(implicit trace: Trace): UIO[Unit]

  def getAndSet(context: Context)(implicit trace: Trace): UIO[Context]

  def updateAndGet(f: Context => Context)(implicit trace: Trace): UIO[Context]

  def locally[R, E, A](context: Context)(effect: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]

}

private[opentelemetry] object ContextStorage {

  /**
   * Use provided [[FiberRef]] as a [[ContextStorage]].
   */
  def fiberRef(ref: FiberRef[Context]): ContextStorage = new ContextStorage {

    override def get(implicit trace: Trace): UIO[Context] =
      ref.get

    override def set(context: Context)(implicit trace: Trace): UIO[Unit] =
      ref.set(context)

    override def getAndSet(context: Context)(implicit trace: Trace): UIO[Context] =
      ref.getAndSet(context)

    override def updateAndGet(f: Context => Context)(implicit trace: Trace): UIO[Context] =
      ref.updateAndGet(f)

    override def locally[R, E, A](context: Context)(effect: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
      ref.locally(context)(effect)

  }

  /**
   * Use OpenTelemetry's default context storage which is backed by a [[ThreadLocal]]. This makes sense only if
   * [[PropagatingSupervisor]] is used.
   */
  def threadLocal: ContextStorage = new ContextStorage {

    override def get(implicit trace: Trace): UIO[Context] = ZIO.succeed(Context.current())

    override def set(context: Context)(implicit trace: Trace): UIO[Unit] = ZIO.succeed(context.makeCurrent()).unit

    override def getAndSet(context: Context)(implicit trace: Trace): UIO[Context] =
      ZIO.succeed {
        val old = Context.current()
        val _   = context.makeCurrent()
        old
      }.uninterruptible

    override def updateAndGet(f: Context => Context)(implicit trace: Trace): UIO[Context] =
      ZIO.succeed {
        val updated = f(Context.current())
        val _       = updated.makeCurrent()
        updated
      }.uninterruptible

    override def locally[R, E, A](context: Context)(effect: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
      ZIO.acquireReleaseWith(get <* set(context))(set)(_ => effect)

  }

}
