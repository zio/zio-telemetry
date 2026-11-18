---
id: status-mapper
title: "StatusMapper"
---

`StatusMapper` decides which OpenTelemetry status a span ends with, based on how the wrapped effect finished. It is the optional `statusMapper` parameter of `Tracing#span`, `Tracing#root`, `Tracing#inSpan`, `Tracing#extractSpan` and `Tracing#spanScoped`, and of the matching methods in `Tracing#aspects`. Without a mapper, a failed span gets the status `ERROR` and a successful one keeps the status `UNSET`.

A mapper can also record the failure as an exception event on the span. For a goal-oriented walkthrough, see [How to Customize Span Status](../how-to/customize-span-status.md).

## Definition

A `StatusMapper[E, A]` carries two partial functions, one for the error channel and one for the success value. Both type parameters are contravariant:

```scala
sealed abstract class StatusMapper[-E, -A](
  val failure: PartialFunction[E, StatusMapper.Result[Throwable]],
  val success: PartialFunction[A, StatusMapper.Result[String]]
)
```

The class is sealed and its constructor is not public, so mappers are created with the methods of the companion object described below. The supporting types are:

```scala
final case class Result[+T](statusCode: StatusCode, error: Option[T] = None)
final case class Failure[-E](pf: PartialFunction[E, Result[Throwable]]) extends StatusMapper[E, Any]
final case class Success[-A](pf: PartialFunction[A, Result[String]]) extends StatusMapper[Any, A]
```

`Result` pairs an `io.opentelemetry.api.trace.StatusCode` (`UNSET`, `OK` or `ERROR`) with an optional payload. For failures the payload is a `Throwable` to record as an exception event. For successes it is a status description. `Failure` only maps errors and `Success` only maps values, and each is itself a `StatusMapper`, so either can be passed directly wherever a mapper is expected.

## Constructors

All constructors live in the `StatusMapper` companion object. The next table summarizes them before the sections that describe each.

| Method                                            | Returns                        | Maps                                             |
|---------------------------------------------------|--------------------------------|--------------------------------------------------|
| `StatusMapper.default`                            | `StatusMapper[Any, Any]`       | Nothing, the built-in behavior applies           |
| `StatusMapper.failure(toStatusCode)(toError)`     | `Failure[E]`                   | Every error to a code and an optional `Throwable`|
| `StatusMapper.failureNoException(toStatusCode)`   | `Failure[E]`                   | Every error to a code, no exception recorded     |
| `StatusMapper.failureThrowable(toStatusCode)`     | `Failure[Throwable]`           | Every `Throwable` to a code, and records it      |
| `StatusMapper.success(toStatusCode)(toError)`     | `Success[A]`                   | Every value to a code and an optional description|
| `StatusMapper.successNoDescription(toStatusCode)` | `Success[A]`                   | Every value to a code                            |
| `StatusMapper.both(failure, success)`             | `StatusMapper[E, A]`           | Combines a `Failure` and a `Success`             |

The mappers built by these methods are total: they define a result for every input. To map only some inputs, construct `StatusMapper.Failure` or `StatusMapper.Success` directly with a partial function.

### default

`StatusMapper.default` has empty partial functions on both sides, so `Tracing` falls back to its built-in behavior, described in [Semantics in Tracing](#semantics-in-tracing). It is the default value of every `statusMapper` parameter.

### failure

`StatusMapper.failure` maps an error of type `E` to a status code and an optional `Throwable`. The first parameter list chooses the status code, the second chooses the exception to record, or `None` to record none:

```scala
def failure[E](toStatusCode: E => StatusCode)(toError: E => Option[Throwable]): Failure[E]
```

We use it when the error type is a domain type rather than an exception, wrapping the domain error in a `Throwable` so it appears on the span:

```scala mdoc:silent
import io.opentelemetry.api.trace.StatusCode
import zio.telemetry.opentelemetry.tracing.StatusMapper

sealed trait OrderError
case object OutOfStock extends OrderError
case class PaymentDeclined(reason: String) extends OrderError

def orderFailures: StatusMapper.Failure[OrderError] =
  StatusMapper.failure[OrderError] {
    case OutOfStock => StatusCode.UNSET
    case _          => StatusCode.ERROR
  } {
    case PaymentDeclined(reason) => Some(new RuntimeException(reason))
    case _                       => None
  }
```

### failureNoException

`StatusMapper.failureNoException` maps the error to a status code without recording an exception event:

```scala
def failureNoException[E](toStatusCode: E => StatusCode): Failure[E]
```

It suits error types that are not throwables and carry no stack trace worth recording:

```scala mdoc:silent
def noException: StatusMapper.Failure[OrderError] =
  StatusMapper.failureNoException[OrderError](_ => StatusCode.ERROR)
```

### failureThrowable

`StatusMapper.failureThrowable` is the shortcut for errors that are already `Throwable`. The throwable is recorded as the exception event:

```scala
def failureThrowable(toStatusCode: Throwable => StatusCode): Failure[Throwable]
```

For example, we can treat illegal arguments as client errors that leave the status `UNSET`, and everything else as `ERROR`:

```scala mdoc:silent
def throwables: StatusMapper.Failure[Throwable] =
  StatusMapper.failureThrowable {
    case _: IllegalArgumentException => StatusCode.UNSET
    case _                           => StatusCode.ERROR
  }
```

### success

`StatusMapper.success` maps a successful value to a status code and an optional description. It allows a span to be marked `ERROR` even though the effect succeeded, which is useful for results such as an HTTP response with a 5xx code:

```scala
def success[A](toStatusCode: A => StatusCode)(toError: A => Option[String]): Success[A]
```

The example maps a response with code 500 or above to `ERROR` with a description and everything else to `OK`:

```scala mdoc:silent
case class Response(code: Int, body: String)

def responses: StatusMapper.Success[Response] =
  StatusMapper.success[Response](r => if (r.code >= 500) StatusCode.ERROR else StatusCode.OK) { r =>
    if (r.code >= 500) Some(s"server returned ${r.code}") else None
  }
```

### successNoDescription

`StatusMapper.successNoDescription` maps the value to a status code only:

```scala
def successNoDescription[A](toStatusCode: A => StatusCode): Success[A]
```

The next definition marks every successful span explicitly as `OK`:

```scala mdoc:silent
def alwaysOk: StatusMapper.Success[Any] =
  StatusMapper.successNoDescription[Any](_ => StatusCode.OK)
```

### both

`StatusMapper.both` combines one `Failure` and one `Success` into a single mapper:

```scala
def both[E, A](failure: Failure[E], success: Success[A]): StatusMapper[E, A]
```

We combine the failure and success mappers defined above to get a mapper for an effect of type `ZIO[R, Throwable, Response]`:

```scala mdoc:silent
def combined: StatusMapper[Throwable, Response] =
  StatusMapper.both(throwables, responses)
```

## Semantics in Tracing

`Tracing` applies the mapper when the body of the span finishes. The rules differ for the failure and success paths.

### On Failure

When the effect fails or is interrupted, `Tracing` looks at the first typed failure of the `Cause`. If the cause contains one and the mapper's `failure` function is defined for it, that `Result` is used. Otherwise, for example when the cause holds only a defect, an interruption, or an error the partial function does not match, the result is `ERROR` with no exception.

The result is then applied to the span:

1. If the status code is `ERROR`, the span status is set to `ERROR` with the pretty-printed `Cause` as its description. A `Result` description never appears here because failure results carry a `Throwable`, not a string.
2. If the status code is anything else, the status is set to that code without a description.
3. If the `Result` has an error, it is recorded on the span with `Span#recordException`, which adds an `exception` event with the exception type, message and stack trace.

### On Success

When the effect succeeds, `Tracing` applies the `success` function if it is defined for the value, and does nothing otherwise, leaving the status `UNSET`. If the result code is `ERROR` and the result has a description, the description is set as the status description. For any other code the description is ignored, since `Tracing` sets a description only together with the `ERROR` code.

### Scoped Spans

`Tracing#spanScoped` accepts a `StatusMapper[Any, Unit]` and uses only its failure side, evaluated when the scope closes with a failure. A successful scope never sets a status. Because the type is `StatusMapper[Any, Unit]` and mappers are contravariant, the practical choices are `StatusMapper.default` and a `Failure[Any]`:

```scala mdoc:silent
def scopedMapper: StatusMapper[Any, Unit] =
  StatusMapper.failure[Any](_ => StatusCode.ERROR)(e => Some(new RuntimeException(e.toString)))
```

## Passing a Mapper to Tracing

Mapper types follow the effect: a span wrapping `ZIO[R, E, A]` takes a `StatusMapper[E, A]`. Since `Failure[E]` is a `StatusMapper[E, Any]`, a failure-only mapper can be passed straight in. The same goes for `Success`. The following program nests two spans, passing the combined mapper to the inner one and a failure-only mapper to the outer one:

```scala mdoc:compile-only
import zio._
import zio.telemetry.opentelemetry.tracing.Tracing

def call: ZIO[Any, Throwable, Response] = ZIO.succeed(Response(503, ""))

val program: ZIO[Tracing, Throwable, Response] =
  ZIO.serviceWithZIO[Tracing] { tracing =>
    tracing.span("outer", statusMapper = throwables) {
      tracing.span("downstream", statusMapper = combined)(call)
    }
  }
```

See [Tracing](tracing.md) for the full signatures of the methods that accept a mapper.
