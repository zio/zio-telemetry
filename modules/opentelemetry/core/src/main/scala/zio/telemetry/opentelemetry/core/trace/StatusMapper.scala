package zio.telemetry.opentelemetry.core.trace

import io.opentelemetry.api.trace.StatusCode
import zio._

/**
 * Maps the result of a wrapped ZIO effect to the status of the [[io.opentelemetry.api.trace.Span]].
 *
 * For more details, see:
 * [[https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/trace/api.md#set-status Set status]]
 *
 * @tparam E
 * @tparam A
 */
sealed trait StatusMapper[-E, -A] {

  private[opentelemetry] def handle(span: Span, exit: Exit[E, A])(implicit trace: Trace): UIO[Unit] =
    exit match {
      case Exit.Success(value) =>
        handleSuccess(span, value)
      case Exit.Failure(cause) =>
        handleFailure(span, cause)
    }

  private[opentelemetry] def handleSuccess(span: Span, a: A)(implicit trace: Trace): UIO[Unit]

  private[opentelemetry] def handleFailure(span: Span, cause: Cause[E])(implicit trace: Trace): UIO[Unit]

}

object StatusMapper {

  /**
   * Empty case allows setting status manually.
   */
  final class Empty[-E, -A] extends StatusMapper[E, A] {

    override def handleSuccess(span: Span, a: A)(implicit trace: Trace): UIO[Unit] =
      ZIO.unit

    override def handleFailure(span: Span, cause: Cause[E])(implicit trace: Trace): UIO[Unit] =
      ZIO.unit
  }

  /**
   * Default case allows overriding the default behavior from the official specification:
   * [[https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/trace/api.md#set-status Set status]]
   *
   * @param success
   * @param failure
   */
  sealed class Default[-E, -A](
    success: PartialFunction[A, Result.Success] = PartialFunction.empty,
    failure: PartialFunction[Cause[E], Result.Failure] = PartialFunction.empty
  ) extends StatusMapper[E, A] {

    override def handleSuccess(span: Span, a: A)(implicit trace: Trace): UIO[Unit] =
      success
        .lift(a)
        .fold(ZIO.unit) { case Result.Success(statusCode, maybeDescription) =>
          if (statusCode == StatusCode.ERROR)
            maybeDescription.fold(span.setStatus(statusCode)) { description =>
              span.setStatus(statusCode, description)
            }
          else
            span.setStatus(statusCode)
        }

    override def handleFailure(span: Span, cause: Cause[E])(implicit trace: Trace): UIO[Unit] = {
      val result =
        failure
          .lift(cause)
          .getOrElse(StatusMapper.Result.Failure(StatusCode.ERROR))

      for {
        _ <-
          result.description match {
            case Some(description) if result.statusCode == StatusCode.ERROR =>
              span.setStatus(result.statusCode, description)
            case _                                                          =>
              span.setStatus(result.statusCode)
          }
        _ <- ZIO.foreach(result.exception)(span.recordException)
      } yield ()
    }

  }

  /**
   * Success case with the default failure case.
   *
   * @param pf
   *   partial function to map the ZIO success to [[io.opentelemetry.api.trace.StatusCode]] and status description
   * @tparam A
   */
  final case class Success[-A](pf: PartialFunction[A, Result.Success]) extends Default[Any, A](success = pf)

  /**
   * Failure case with the default success case.
   *
   * @param pf
   *   partial function to map the ZIO failure to [[io.opentelemetry.api.trace.StatusCode]] and [[java.lang.Throwable]].
   *   The latter is used to record the exception, see:
   *   [[https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/trace/exceptions.md#recording-an-exception]]
   * @tparam E
   */
  final case class Failure[-E](pf: PartialFunction[Cause[E], Result.Failure]) extends Default[E, Any](failure = pf)

  /**
   * The equivalent of bi-map for StatusMapper.
   *
   * @param success
   *   status mapper for a success case
   * @param failure
   *   status mapper for a failure case
   *
   * @tparam E
   * @tparam A
   */
  final case class Both[-E, -A](
    success: Success[A],
    failure: Failure[E]
  ) extends Default[E, A](success.pf, failure.pf)

  sealed trait Result

  object Result {
    final case class Success(statusCode: StatusCode, description: Option[String] = None) extends Result

    final case class Failure(
      statusCode: StatusCode,
      description: Option[String] = None,
      exception: Option[Throwable] = None
    ) extends Result
  }

  /**
   * It doesn't set the status on a scope close. Use it when you want to set the status manually.
   */
  val empty: Empty[Any, Any] =
    new Empty

  /**
   * It follows the offical specification:
   * [[https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/trace/api.md#set-status Set status]]
   */
  val default: Default[Any, Any] =
    new Default()

  /**
   * Overrides both success and failure cases of the default status mapper.
   *
   * Usage example:
   * {{{
   *   StatusMapper.both(
   *     StatusMapper.failureThrowable(StatusCode.ERROR),
   *     StatusMapper.successNoDescription[Any](_ => StatusCode.OK)
   *   )
   * }}}
   *
   * @param success
   * @param failure
   * @return
   */
  def both[E, A](success: Success[A], failure: Failure[E]): Both[E, A] =
    Both(success, failure)

  private def failureFromZIOFailure[E](e: E => Result.Failure): Failure[E] =
    Failure(Function.unlift(cause => cause.failureOption.map(e)))

  /**
   * Overrides the status code, description, and exception for a failure case stemming from an (expected) effect
   * failure.
   *
   * Usage example:
   * {{{
   *   StatusMapper.failure[MyError](_ => StatusCode.ERROR)(e => Some(e.message))(e => Some(new RuntimeException(e.message)))
   * }}}
   *
   * @param toStatusCode
   * @param toDescription
   * @param toException
   * @return
   */
  def failure[E](toStatusCode: E => StatusCode)(toDescription: E => Option[String])(
    toException: E => Option[Throwable]
  ): Failure[E] =
    failureFromZIOFailure(e => Result.Failure(toStatusCode(e), toDescription(e), toException(e)))

  /**
   * Overrides the status code, description, and exception for a failure case stemming from any effect error.
   *
   * Usage example:
   * {{{
   *   StatusMapper.failure[MyError](_ => StatusCode.ERROR)(e => e.failureOption.map(e.message))(e => e.failureOption.map(e => new RuntimeException(e.message)))
   * }}}
   *
   * @param toStatusCode
   * @param toDescription
   * @param toException
   * @return
   */
  def failureCause[E](toStatusCode: Cause[E] => StatusCode)(toDescription: Cause[E] => Option[String])(
    toException: Cause[E] => Option[Throwable]
  ): Failure[E] =
    Failure { case e => Result.Failure(toStatusCode(e), toDescription(e), toException(e)) }

  /**
   * Overrides the status code and exception for a failure case stemming from an (expected) effect failure.
   *
   * Usage example:
   * {{{
   *   StatusMapper.failureNoDescription[MyError](_ => StatusCode.ERROR)(e => Some(new RuntimeException(e.message)))
   * }}}
   *
   * @param toStatusCode
   * @param toException
   * @return
   */
  def failureNoDescription[E](toStatusCode: E => StatusCode)(toException: E => Option[Throwable]): Failure[E] =
    failureFromZIOFailure(e => Result.Failure(toStatusCode(e), exception = toException(e)))

  /**
   * Overrides the status code and exception for a failure case stemming from any effect error.
   *
   * Usage example:
   * {{{
   *   StatusMapper.failureNoDescription[MyError](_ => StatusCode.ERROR)(e => e.failureOption.map(e => new RuntimeException(e.message))))
   * }}}
   *
   * @param toStatusCode
   * @param toException
   * @return
   */
  def failureCauseNoDescription[E](toStatusCode: Cause[E] => StatusCode)(
    toException: Cause[E] => Option[Throwable]
  ): Failure[E] =
    Failure { case e => Result.Failure(toStatusCode(e), exception = toException(e)) }

  /**
   * Overrides the status code and description, but skips exception for a failure case stemming from an (expected)
   * effect failure.
   *
   * Usage example:
   * {{{
   *   StatusMapper.failureNoException[MyError](_ => StatusCode.ERROR)(e => Some(e.message))
   * }}}
   *
   * @param toStatusCode
   * @return
   */
  def failureNoException[E](toStatusCode: E => StatusCode)(toDescription: E => Option[String]): Failure[E] =
    failureFromZIOFailure(e => Result.Failure(toStatusCode(e), description = toDescription(e)))

  /**
   * Overrides the status code and description, but skips exception for any effect error.
   *
   * Usage example:
   * {{{
   *   StatusMapper.failureNoException[MyError](_ => StatusCode.ERROR)(e => e.failureOption(e => e.message)))
   * }}}
   *
   * @param toStatusCode
   * @return
   */
  def failureCauseNoException[E](toStatusCode: Cause[E] => StatusCode)(
    toDescription: Cause[E] => Option[String]
  ): Failure[E] =
    Failure { case e => Result.Failure(toStatusCode(e), description = toDescription(e)) }

  /**
   * Overrides the status code and adds an exception for a failure case stemming from an (expected) effect failure.
   *
   * Usage example:
   * {{{
   *   StatusMapper.failureThrowable(_ => StatusCode.ERROR)
   * }}}
   *
   * @param toStatusCode
   * @return
   */
  def failureThrowable(toStatusCode: Throwable => StatusCode): Failure[Throwable] =
    failureFromZIOFailure(e => Result.Failure(toStatusCode(e), None, Option(e)))

  /**
   * Overrides the status code and description for a success case.
   *
   * Usage example:
   * {{{
   *   StatusMapper.success[Response] { resp =>
   *     if(resp.code == 500) StatusCode.ERROR else StatusCode.OK
   *   } { resp =>
   *     if(resp.code == 500) Some(resp.errorMessage) else None
   *   }
   * }}}
   * @param toStatusCode
   * @param toDescription
   * @return
   */
  def success[A](toStatusCode: A => StatusCode)(toDescription: A => Option[String]): Success[A] =
    Success { case a => Result.Success(toStatusCode(a), toDescription(a)) }

  /**
   * Overrides the status code for a success case.
   *
   * Usage example:
   * {{{
   *   StatusMapper.successNoDescription[Response](_ => StatusCode.OK)
   * }}}
   * @param toStatusCode
   * @return
   */
  def successNoDescription[A](toStatusCode: A => StatusCode): Success[A] =
    Success { case a => Result.Success(toStatusCode(a)) }

}
