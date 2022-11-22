package zio.telemetry.opentelemetry.tracing

import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import zio._

import java.util.concurrent.ConcurrentHashMap
import scala.annotation.nowarn

@nowarn("msg=discarded non-Unit value")
final class PropagatingSupervisor extends Supervisor[Unit] {

  private val storage = new ConcurrentHashMap[FiberId, Span]()

  override def value(implicit trace: Trace): UIO[Unit] = ZIO.unit

  override def onStart[R, E, A](
    environment: ZEnvironment[R],
    effect: ZIO[R, E, A],
    parent: Option[Fiber.Runtime[Any, Any]],
    fiber: Fiber.Runtime[E, A]
  )(implicit unsafe: Unsafe): Unit = {
    val span = Span.current()
    if (span != null) storage.put(fiber.id, span)
    else storage.put(fiber.id, Span.fromContext(Context.root()))
  }

  override def onEnd[R, E, A](value: Exit[E, A], fiber: Fiber.Runtime[E, A])(implicit unsafe: Unsafe): Unit = {
    storage.remove(fiber.id)
    Context.root().makeCurrent()
  }

  override def onSuspend[E, A](fiber: Fiber.Runtime[E, A])(implicit unsafe: Unsafe): Unit = {
    val span = Span.current()
    if (span != null) storage.put(fiber.id, span)
    else storage.put(fiber.id, Span.fromContext(Context.root()))
    Context.root().makeCurrent()
  }

  override def onResume[E, A](fiber: Fiber.Runtime[E, A])(implicit unsafe: Unsafe): Unit = {
    val span = storage.get(fiber.id)
    if (span != null) span.makeCurrent()
  }

}
