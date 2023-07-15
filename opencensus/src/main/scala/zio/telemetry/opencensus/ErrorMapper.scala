package zio.telemetry.opencensus

import io.opencensus.trace.Status

case class ErrorMapper[-E](body: PartialFunction[E, Status])

object ErrorMapper {

  val default: ErrorMapper[Any] =
    ErrorMapper(PartialFunction.empty)

}
