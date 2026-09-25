package zio.telemetry.opentelemetry.core.internal

import zio.{Cause, FiberFailure}

private[opentelemetry] object Utils {
  def causeToThrowable(cause: Cause[Any]): Throwable =
    (cause.failures, cause.defects, cause.isInterrupted) match {
      case ((failure: Throwable) :: Nil, Nil, false) => failure
      case (Nil, defect :: Nil, false)               => defect
      case _                                         => FiberFailure(cause)
    }
}
