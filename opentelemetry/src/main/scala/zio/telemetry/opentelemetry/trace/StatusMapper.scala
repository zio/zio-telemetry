package zio.telemetry.opentelemetry.trace

import io.opentelemetry.api.trace.StatusCode
import zio._

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
 *   [[https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/trace/exceptions.md#recording-an-exception]]
 * @param success
 *   partial function to map the ZIO success to [[io.opentelemetry.api.trace.StatusCode]] and status description
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

  final class Empty[-E, -A] extends StatusMapper[E, A] {
    override def handleSuccess(span: Span, a: A)(implicit trace: Trace): UIO[Unit] =
      ZIO.unit

    override def handleFailure(span: Span, cause: Cause[E])(implicit trace: Trace): UIO[Unit] =
      ZIO.unit
  }

  final case class Success[-A](pf: PartialFunction[A, Result.Success]) extends StatusMapper[Any, A] {

    override def handleSuccess(span: Span, a: A)(implicit trace: Trace): UIO[Unit] =
      pf
        .lift(a)
        .fold(ZIO.unit) { case Result.Success(statusCode, maybeDescription) =>
          if (statusCode == StatusCode.ERROR)
            maybeDescription.fold(span.setStatus(statusCode)) { description =>
              span.setStatus(statusCode, description)
            }
          else
            span.setStatus(statusCode)
        }

    private[opentelemetry] def handleFailure(span: Span, cause: Cause[Any])(implicit trace: Trace): UIO[Unit] =
      ZIO.unit

  }

  final case class Failure[-E](pf: PartialFunction[E, Result.Failure]) extends StatusMapper[E, Any] {

    private[opentelemetry] def handleFailure(span: Span, cause: Cause[E])(implicit trace: Trace): UIO[Unit] = {
      val result =
        cause.failureOption
          .flatMap(pf.lift)
          .getOrElse(StatusMapper.Result.Failure(StatusCode.ERROR))

      for {
        _ <- if (result.statusCode == StatusCode.ERROR)
               span.setStatus(result.statusCode, cause.prettyPrint)
             else
               span.setStatus(result.statusCode)
        _ <- result.exception.fold(ZIO.unit)(span.recordException)
      } yield ()
    }

    override def handleSuccess(span: Span, a: Any)(implicit trace: Trace): UIO[Unit] =
      ZIO.unit

  }

  final case class Both[-E, -A](
    val success: Success[A],
    val failure: Failure[E]
  ) extends StatusMapper[E, A] {

    override def handleSuccess(span: Span, a: A)(implicit trace: Trace): UIO[Unit] =
      success.handleSuccess(span, a)

    override def handleFailure(span: Span, cause: Cause[E])(implicit trace: Trace): UIO[Unit] =
      failure.handleFailure(span, cause)

  }

  sealed trait Result

  object Result {
    final case class Success(statusCode: StatusCode, description: Option[String] = None)  extends Result
    final case class Failure(statusCode: StatusCode, exception: Option[Throwable] = None) extends Result
  }

  val default: Both[Any, Any] =
    both(Success(PartialFunction.empty), Failure(PartialFunction.empty))

  val empty: Empty[Any, Any] =
    new Empty

  def both[E, A](success: Success[A], failure: Failure[E]): Both[E, A] =
    Both(success, failure)

  def failure[E](toStatusCode: E => StatusCode)(toError: E => Option[Throwable]): Failure[E] =
    Failure { case e => Result.Failure(toStatusCode(e), toError(e)) }

  def failureNoException[E](toStatusCode: E => StatusCode): Failure[E] =
    Failure { case e => Result.Failure(toStatusCode(e)) }

  def failureThrowable(toStatusCode: Throwable => StatusCode): Failure[Throwable] =
    Failure { case e => Result.Failure(toStatusCode(e), Option(e)) }

  def success[A](toStatusCode: A => StatusCode)(toError: A => Option[String]): Success[A] =
    Success { case a => Result.Success(toStatusCode(a), toError(a)) }

  def successNoDescription[A](toStatusCode: A => StatusCode): Success[A] =
    Success { case a => Result.Success(toStatusCode(a)) }

}
