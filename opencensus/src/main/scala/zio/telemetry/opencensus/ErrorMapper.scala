package zio.telemetry.opencensus

import io.opencensus.trace.Status

case class ErrorMapper[E](body: PartialFunction[E, Status])

object ErrorMapper {

  def default[E]: ErrorMapper[E] =
    ErrorMapper[E](Map.empty)

}
