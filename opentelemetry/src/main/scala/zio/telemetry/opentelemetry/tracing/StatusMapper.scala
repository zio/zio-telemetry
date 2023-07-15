package zio.telemetry.opentelemetry.tracing

import io.opentelemetry.api.trace.StatusCode
import zio.telemetry.opentelemetry.tracing.StatusMapper.StatusMapperResult

case class StatusMapper[-E, -A](
  failure: PartialFunction[E, StatusMapperResult[Throwable]],
  success: PartialFunction[A, StatusMapperResult[String]]
)

object StatusMapper {

  val default: StatusMapper[Any, Any] =
    StatusMapper(PartialFunction.empty, PartialFunction.empty)

  final case class StatusMapperResult[+T](statusCode: StatusCode, error: Option[T] = None)

  def failure(statusCode: StatusCode): StatusMapper[Throwable, Any] = StatusMapper(
    { case e => StatusMapperResult(statusCode, Option(e)) },
    PartialFunction.empty
  )

  def success[A](
    statusCode: StatusCode,
    f: A => Option[String] = { (_: A) => Option.empty[String] }
  ): StatusMapper[Any, A] =
    StatusMapper(
      PartialFunction.empty,
      { case a => StatusMapperResult(statusCode, f(a)) }
    )
}
