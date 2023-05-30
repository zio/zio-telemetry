package zio.telemetry.opentelemetry.tracing

import io.opentelemetry.api.trace.StatusCode

case class ErrorMapper[E, A](
  body: PartialFunction[E, StatusCode],
  errorFromResult: PartialFunction[A, (StatusCode, Option[Throwable])] = Map.empty[A, (StatusCode, Option[Throwable])],
  toThrowable: Option[E => Throwable] = None
)

object ErrorMapper {

  def default[E, A]: ErrorMapper[E, A] =
    ErrorMapper[E, A](Map.empty)

}
