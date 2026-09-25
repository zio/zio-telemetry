package zio.telemetry.opentelemetry.core.internal

import zio.Cause

object CauseExtensions {
  implicit class CauseExtensions[E](cause: Cause[E]) {
    def toUnwrappedThrowable: Throwable = Utils.causeToThrowable(cause)
  }
}
