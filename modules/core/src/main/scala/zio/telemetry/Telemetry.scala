package zio.telemetry

import zio.Cause
import zio.FiberRef
import zio.UIO
import zio.URIO
import zio.clock.Clock

trait Telemetry extends Serializable {
  def telemetry: Telemetry.Service
}

object Telemetry {

  trait Service {
    type A

    def currentSpan: FiberRef[A]
    def root(opName: String): URIO[Clock, A]
    def span(span: A, opName: String): URIO[Clock, A]
    def finish(span: A): URIO[Clock, Unit]
    def error(span: A, cause: Cause[_], tagError: Boolean, logError: Boolean): UIO[Unit]
  }

}
