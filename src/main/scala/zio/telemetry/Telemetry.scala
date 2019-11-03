package zio.telemetry

import zio.FiberRef
import zio.URIO
import zio.clock.Clock

trait Telemetry[A] extends Serializable {
  def telemetry: Telemetry.Service[A]
}

object Telemetry {

  trait Service[A] {
    def root(opName: String): URIO[Clock, A]
    def span(span: A, opName: String): URIO[Clock, A]
    def currentSpan: FiberRef[A]
    def finish(span: A): URIO[Clock, Unit]
  }

}
