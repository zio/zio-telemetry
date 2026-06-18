package zio.telemetry.opentelemetry.agent

import io.opentelemetry.context.Context
import zio._
import zio.internal.FiberRuntime

object ContextBridge {

  def installSupervisor(fiberRef: FiberRef[Context]): ZLayer[Any, Nothing, Unit] = {
    val link: Context => Unit = { ctx =>
      ctx.makeCurrent()
      ()
    }

    val supervisor = new Supervisor[Unit] {
      override def value(implicit trace: Trace): UIO[Unit] = ZIO.unit

      override def onStart[R, E, A](
        environment: ZEnvironment[R],
        effect: ZIO[R, E, A],
        parent: Option[Fiber.Runtime[Any, Any]],
        fiber: Fiber.Runtime[E, A]
      )(implicit unsafe: Unsafe): Unit = ()

      override def onEnd[R, E, A](
        value: Exit[E, A],
        fiber: Fiber.Runtime[E, A]
      )(implicit unsafe: Unsafe): Unit = ()

      override def onSuspend[E, A](
        fiber: Fiber.Runtime[E, A]
      )(implicit unsafe: Unsafe): Unit =
        link(Context.root())

      override def onResume[E, A](
        fiber: Fiber.Runtime[E, A]
      )(implicit unsafe: Unsafe): Unit = {
        val ctx = fiber.asInstanceOf[FiberRuntime[E, A]].getFiberRef(fiberRef)
        link(ctx)
      }
    }

    Runtime.addSupervisor(supervisor)
  }

}
