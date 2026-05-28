package zio.telemetry.opentelemetry.agent

import zio._
import zio.telemetry.opentelemetry.core
import zio.telemetry.opentelemetry.core.context.ContextPropagator
import zio.telemetry.opentelemetry.core.context.internal.ContextStorage

object OpenTelemetry {

  def global(logAnnotated: Boolean = false)(implicit trace: Trace): TaskLayer[core.OpenTelemetry] =
    ZLayer.scoped {
      for {
        underlying <- ZIO.attempt(io.opentelemetry.api.GlobalOpenTelemetry.get())
        propagator  = ContextPropagator.fromJava(underlying.getPropagators)
        storage    <- createStorage
      } yield core.OpenTelemetry.make(storage, underlying, propagator, logAnnotated)
    }

  private def createStorage(implicit trace: Trace): URIO[Scope, ContextStorage] =
    ZioAgentContext.getAgentFiberRef() match {
      case Some(fiberRef) =>
        ZIO.logInfo("zio-opentelemetry-agent: agent-provided FiberRef detected") *>
          ZIO.succeed(new FiberRefContextStorage(fiberRef))
      case None           =>
        ZIO.logWarning("zio-opentelemetry-agent: agent not detected, falling back to JavaOtelThreadLocal") *>
          ZIO.succeed(ContextStorage.JavaOtelThreadLocal)
    }

}
