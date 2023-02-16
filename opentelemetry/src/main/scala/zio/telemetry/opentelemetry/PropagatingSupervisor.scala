package zio.telemetry.opentelemetry

import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import zio.Supervisor.Propagation
import zio._

import java.util.concurrent.ConcurrentHashMap
import scala.annotation.nowarn

@nowarn("msg=discarded non-Unit value")
final class PropagatingSupervisor extends Supervisor[Unit] {

  private val storage = new ConcurrentHashMap[Fiber.Id, Span]()

  override def value: UIO[Unit] = ZIO.unit

  override def unsafeOnStart[R, E, A](
    environment: R,
    effect: ZIO[R, E, A],
    parent: Option[Fiber.Runtime[Any, Any]],
    fiber: Fiber.Runtime[E, A]
  ): Propagation = {
    val span = Span.current()
    if (span != null) storage.put(fiber.id, span)
    else storage.put(fiber.id, Span.fromContext(Context.root()))

    Propagation.Continue
  }

  override def unsafeOnEnd[R, E, A](value: Exit[E, A], fiber: Fiber.Runtime[E, A]): Propagation = {
    storage.remove(fiber.id)
    Context.root().makeCurrent()

    Propagation.Continue
  }

  override def unsafeOnSuspend[E, A](fiber: Fiber.Runtime[E, A]): Unit = {
    val span = Span.current()
    if (span != null) storage.put(fiber.id, span)
    else storage.put(fiber.id, Span.fromContext(Context.root()))
    Context.root().makeCurrent()
  }

  override def unsafeOnResume[E, A](fiber: Fiber.Runtime[E, A]): Unit = {
    val span = storage.get(fiber.id)
    if (span != null) span.makeCurrent()
  }

}
