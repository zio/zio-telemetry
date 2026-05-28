package zio.telemetry.opentelemetry.agent

import io.opentelemetry.context.Context
import zio._

object ZioAgentContext {

  private[agent] def getAgentFiberRef(): Option[FiberRef[Context]] = None

  def isAgentAttached: Boolean = getAgentFiberRef().isDefined

}
