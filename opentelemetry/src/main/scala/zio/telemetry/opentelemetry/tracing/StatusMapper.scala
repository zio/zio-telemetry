package zio.telemetry.opentelemetry.tracing

import io.opentelemetry.api.trace.StatusCode
import zio.telemetry.opentelemetry.tracing.StatusMapper.Result

/**
 * Maps the result of a wrapped ZIO effect to the status of the [[io.opentelemetry.api.trace.Span]].
 *
 * For more details, see:
 * [[https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/trace/api.md#set-status Set status]]
 *
 * Usage examples:
 * {{{
 *   StatusMapper.failure[MyError](_ => StatusCode.ERROR)(e => Some(new RuntimeException(e.message)))
 *   StatusMapper.failureNoException(_ => StatusCode.ERROR)
 *   StatusMapper.failureThrowable(StatusCode.ERROR)
 *
 *   StatusMapper.success[Response] {
 *     resp => if(resp.code == 500) StatusCode.ERROR else StatusCode.OK
 *   } { resp =>
 *     if(resp.code == 500) Some(resp.errorMessage) else None
 *   }
 *   StatusMapper.successNoDescription[Response](_ => StatusCode.OK)
 *
 *   StatusMapper.both(
 *     StatusMapper.failureThrowable(StatusCode.ERROR),
 *     StatusMapper.successNoDescription[Any](_ => StatusCode.OK)
 *   )
 * }}}
 * @param failure
 *   partial function to map the ZIO failure to [[io.opentelemetry.api.trace.StatusCode]] and [[java.lang.Throwable]].
 *   The latter is used to record the exception, see:
 *   [[https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/trace/semantic_conventions/exceptions.md#recording-an-exception Recording an exception]]
 * @param success
 *   partial function to map the ZIO success to [[io.opentelemetry.api.trace.StatusCode]] and status description
 * @tparam E
 * @tparam A
 */
sealed abstract class StatusMapper[-E, -A](
  val failure: PartialFunction[E, Result[Throwable]],
  val success: PartialFunction[A, Result[String]]
)

object StatusMapper {

  final case class Failure[-E](pf: PartialFunction[E, Result[Throwable]])
      extends StatusMapper[E, Any](pf, PartialFunction.empty)
  final case class Success[-A](pf: PartialFunction[A, Result[String]])
      extends StatusMapper[Any, A](PartialFunction.empty, pf)

  final case class Result[+T](statusCode: StatusCode, error: Option[T] = None)

  private[tracing] def apply[E, A](
    failure: PartialFunction[E, Result[Throwable]],
    success: PartialFunction[A, Result[String]]
  ): StatusMapper[E, A] =
    new StatusMapper[E, A](failure, success) {}

  def both[E, A](failure: Failure[E], success: Success[A]): StatusMapper[E, A] =
    StatusMapper[E, A](failure.pf, success.pf)

  val default: StatusMapper[Any, Any] =
    StatusMapper(PartialFunction.empty, PartialFunction.empty)

  def failure[E](toStatusCode: E => StatusCode)(toError: E => Option[Throwable]): Failure[E] =
    Failure { case e => Result(toStatusCode(e), toError(e)) }

  def failureNoException[E](toStatusCode: E => StatusCode): Failure[E] =
    Failure { case e => Result(toStatusCode(e)) }

  def failureThrowable(toStatusCode: Throwable => StatusCode): Failure[Throwable] =
    Failure { case e => Result(toStatusCode(e), Option(e)) }

  def success[A](toStatusCode: A => StatusCode)(toError: A => Option[String]): Success[A] =
    Success { case a => Result(toStatusCode(a), toError(a)) }

  def successNoDescription[A](toStatusCode: A => StatusCode): Success[A] =
    Success { case a => Result(toStatusCode(a)) }

}
