package zio.telemetry.opentelemetry.tracing

import io.opentelemetry.api.trace.StatusCode
import zio.telemetry.opentelemetry.tracing.StatusMapper.StatusMapperResult

case class StatusMapper[E, A](
  failure: PartialFunction[E, StatusMapperResult[Throwable]],
  success: PartialFunction[A, StatusMapperResult[String]]
)

object StatusMapper {

  def default[E, A]: StatusMapper[E, A] =
    StatusMapper[E, A](Map.empty, Map.empty)

  final case class StatusMapperResult[T](statusCode: StatusCode, error: Option[T] = None)

  def failure[A](statusCode: StatusCode): StatusMapper[Throwable, A] = StatusMapper(
    { case e => StatusMapperResult(statusCode, Option(e)) },
    Map.empty
  )

  def success[E, A](
    statusCode: StatusCode,
    f: A => Option[String] = { _: A => Option.empty[String] }
  ): StatusMapper[E, A] =
    StatusMapper(
      Map.empty,
      { case a => StatusMapperResult(statusCode, f(a)) }
    )
}
