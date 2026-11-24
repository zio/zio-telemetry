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
 * import io.opentelemetry.api.trace.StatusCode
 *
 * final case class MyError(message: String)
 * final case class Response(code: Int, errorMessage: String)
 *
 * val failureWithEx    = StatusMapper.failure[MyError](_ => StatusCode.ERROR)(e => Some(new RuntimeException(e.message)))
 * val failureNoEx      = StatusMapper.failureNoException[MyError](_ => StatusCode.ERROR)
 * val failureThrowable = StatusMapper.failureThrowable(_ => StatusCode.ERROR)
 *
 * val successWithDescription =
 *   StatusMapper.success[Response](resp => if (resp.code == 500) StatusCode.ERROR else StatusCode.OK)(resp =>
 *     if (resp.code == 500) Some(resp.errorMessage) else None
 *   )
 * val successNoDescription = StatusMapper.successNoDescription[Response](_ => StatusCode.OK)
 *
 * val both = StatusMapper.both(failureThrowable, StatusMapper.successNoDescription[Any](_ => StatusCode.OK))
 * }}}
 * @param failure
 *   partial function to map the ZIO failure to [[io.opentelemetry.api.trace.StatusCode]] and [[java.lang.Throwable]].
 *   The latter is used to record the exception, see:
 *   [[https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/trace/exceptions.md#recording-an-exception]]
 * @param success
 *   partial function to map the ZIO success to [[io.opentelemetry.api.trace.StatusCode]] and status description
 * @tparam E
 *   type of the ZIO failure
 * @tparam A
 *   type of the ZIO success
 */
sealed abstract class StatusMapper[-E, -A](
  val failure: PartialFunction[E, Result[Throwable]],
  val success: PartialFunction[A, Result[String]]
)

/**
 * Constructors for [[StatusMapper]]. Most factories take a function from the ZIO failure or success to a status code.
 * [[both]] combines a failure and a success mapping, and [[default]] maps nothing.
 */
object StatusMapper {

  /**
   * A [[StatusMapper]] for failures only. Build it with [[StatusMapper.failure]], [[StatusMapper.failureNoException]]
   * or [[StatusMapper.failureThrowable]].
   */
  final case class Failure[-E](pf: PartialFunction[E, Result[Throwable]])
      extends StatusMapper[E, Any](pf, PartialFunction.empty)

  /**
   * A [[StatusMapper]] for successes only. Build it with [[StatusMapper.success]] or
   * [[StatusMapper.successNoDescription]].
   */
  final case class Success[-A](pf: PartialFunction[A, Result[String]])
      extends StatusMapper[Any, A](PartialFunction.empty, pf)

  /**
   * The span status to set, with an optional value. For a failure, `error` is recorded on the span as an exception. For
   * a success, `error` is the status description, and it is used only when `statusCode` is `StatusCode.ERROR`.
   *
   * @param statusCode
   *   status to set on the span
   * @param error
   *   for a failure, the throwable recorded on the span as an exception. For a success, the status description, used
   *   only when `statusCode` is `StatusCode.ERROR`
   */
  final case class Result[+T](statusCode: StatusCode, error: Option[T] = None)

  private[tracing] def apply[E, A](
    failure: PartialFunction[E, Result[Throwable]],
    success: PartialFunction[A, Result[String]]
  ): StatusMapper[E, A] =
    new StatusMapper[E, A](failure, success) {}

  /**
   * Combines a failure mapping and a success mapping into one [[StatusMapper]].
   */
  def both[E, A](failure: Failure[E], success: Success[A]): StatusMapper[E, A] =
    StatusMapper[E, A](failure.pf, success.pf)

  /**
   * A [[StatusMapper]] that maps nothing. A failed effect sets the span status to `StatusCode.ERROR` with the
   * pretty-printed cause as the description, and records no exception. A successful effect leaves the span status
   * unchanged.
   */
  val default: StatusMapper[Any, Any] =
    StatusMapper(PartialFunction.empty, PartialFunction.empty)

  /**
   * Maps each failure `e` to the span status `toStatusCode(e)`. When `toError(e)` is `Some`, that throwable is recorded
   * on the span as an exception. When the status is `StatusCode.ERROR`, the status description is the pretty-printed
   * cause.
   *
   * @param toStatusCode
   *   status to set for the failure
   * @param toError
   *   optional throwable to record on the span
   */
  def failure[E](toStatusCode: E => StatusCode)(toError: E => Option[Throwable]): Failure[E] =
    Failure { case e => Result(toStatusCode(e), toError(e)) }

  /**
   * Maps each failure `e` to the span status `toStatusCode(e)`, and records no exception.
   */
  def failureNoException[E](toStatusCode: E => StatusCode): Failure[E] =
    Failure { case e => Result(toStatusCode(e)) }

  /**
   * Maps each failure `e` to the span status `toStatusCode(e)`, and records `e` on the span as an exception.
   */
  def failureThrowable(toStatusCode: Throwable => StatusCode): Failure[Throwable] =
    Failure { case e => Result(toStatusCode(e), Option(e)) }

  /**
   * Maps each success `a` to the span status `toStatusCode(a)`. When that status is `StatusCode.ERROR` and `toError(a)`
   * is `Some`, the string is set as the status description.
   *
   * @param toStatusCode
   *   status to set for the success
   * @param toError
   *   optional status description, used only when the status is `StatusCode.ERROR`
   */
  def success[A](toStatusCode: A => StatusCode)(toError: A => Option[String]): Success[A] =
    Success { case a => Result(toStatusCode(a), toError(a)) }

  /**
   * Maps each success `a` to the span status `toStatusCode(a)`, without a status description.
   */
  def successNoDescription[A](toStatusCode: A => StatusCode): Success[A] =
    Success { case a => Result(toStatusCode(a)) }

}
