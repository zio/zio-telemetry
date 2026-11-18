---
id: customize-span-status
title: "How to Customize Span Status"
description: "Decide which failures and successes mark a span as an error by passing a StatusMapper to Tracing."
keywords:
  - "OpenTelemetry"
  - "span status"
  - "StatusMapper"
  - "error handling"
---

In this guide, we will control the status that ZIO Telemetry puts on a span when the wrapped effect finishes. By the end, expected failures such as "not found" leave the span status unset, real failures mark it as an error with an exception event, and an HTTP-style response with a `5xx` code marks the span as failed even though the effect succeeded.

We start from the default behavior, then add a failure mapper, a success mapper, and finally combine both.

## The Problem

By default, every failed effect marks its span with status `ERROR` and every successful effect leaves the status `UNSET`. That is a poor fit for two common situations.

First, some failures are part of normal operation. A lookup that returns "not found" is a legitimate answer, but the default makes the span look like an incident in your tracing backend and inflates error-rate dashboards. Second, some effects succeed with a value that represents a failure, such as a response with status code `503`. The default records nothing, so the span looks healthy.

For example, consider a lookup that models both situations:

```scala mdoc:silent
import io.opentelemetry.api.trace.StatusCode
import zio._
import zio.telemetry.opentelemetry.tracing.{StatusMapper, Tracing}

object Domain {
  sealed trait LookupError
  case object NotFound                         extends LookupError
  case class Unavailable(reason: String) extends LookupError

  case class Response(code: Int, message: String)
}

import Domain._
```

Here is the effect we want to trace. It fails with `NotFound` for unknown ids, and returns a `503` response when the backend is overloaded:

```scala mdoc:silent
def lookup(id: String): IO[LookupError, Response] =
  id match {
    case "missing"  => ZIO.fail(NotFound)
    case "down"     => ZIO.fail(Unavailable("backend is down"))
    case "overload" => ZIO.succeed(Response(503, "try again later"))
    case _          => ZIO.succeed(Response(200, "ok"))
  }
```

Wrapped in a span without any configuration, `lookup("missing")` is reported as an error and `lookup("overload")` is reported as unset. A `StatusMapper` lets us change both.

## Prerequisites

We need the `zio-opentelemetry` module. The `StatusMapper` API lives in the `zio.telemetry.opentelemetry.tracing` package and needs the OpenTelemetry `StatusCode` enum, which comes with the OpenTelemetry API transitively:

```scala
libraryDependencies += "dev.zio" %% "zio-opentelemetry" % "@VERSION@"
```

We assume that a `Tracing` service is already available. See [OpenTelemetry layers](../reference/opentelemetry-layers.md) for how to build one, and the [`StatusMapper` reference](../reference/status-mapper.md) for the full list of factory methods.

## Understanding the Default Behavior

Before customizing anything, it helps to know exactly what `Tracing#span`, `Tracing#root` and the other span methods do when no mapper is given. The default is `StatusMapper.default`, which has no cases, and the span methods then behave as follows:

- On an expected failure or a defect, the status is `ERROR` and the description is the pretty-printed `Cause`. No exception event is recorded.
- On success, the status is left untouched (`UNSET`).

A mapper changes this per outcome, which gives us two independent extension points: one for failures and one for successes.

## Mapping Failures to a Status Code

A failure mapper receives the expected error of the effect and returns the status code to set. The simplest factory, `StatusMapper.failureNoException`, takes only that function.

We want `NotFound` to leave the span unset and every other failure to be an error:

```scala mdoc:silent
def failureMapper: StatusMapper.Failure[LookupError] =
  StatusMapper.failureNoException[LookupError] {
    case NotFound => StatusCode.UNSET
    case _        => StatusCode.ERROR
  }
```

We pass the mapper to the span through the `statusMapper` parameter of the `span` aspect:

```scala mdoc:compile-only
def tracedLookup(id: String): ZIO[Tracing, LookupError, Response] =
  ZIO.serviceWithZIO[Tracing] { tracing =>
    import tracing.aspects._

    lookup(id) @@ span("lookup", statusMapper = failureMapper)
  }
```

When the returned status code is `ERROR`, the span description is the pretty-printed `Cause` of the failure. For any other status code no description is set.

:::note
A failure mapper only sees expected failures. Defects and interruptions have no typed error to map, so they always produce status `ERROR` regardless of the mapper.
:::

## Recording the Failure as an Exception Event

Sometimes the status code alone loses information. `StatusMapper.failure` takes a second function that turns the error into an optional `Throwable`, which is then recorded on the span as an exception event with the standard `exception.type` and `exception.message` attributes.

We record an exception for `Unavailable`, and nothing for `NotFound`:

```scala mdoc:silent
def failureWithException: StatusMapper.Failure[LookupError] =
  StatusMapper.failure[LookupError] {
    case NotFound => StatusCode.UNSET
    case _        => StatusCode.ERROR
  } {
    case Unavailable(reason) => Some(new RuntimeException(reason))
    case NotFound            => None
  }
```

If the error type already is a `Throwable`, `StatusMapper.failureThrowable` does the same in one step. It sets the status you choose and records the throwable itself:

```scala mdoc:silent
def throwableMapper: StatusMapper.Failure[Throwable] =
  StatusMapper.failureThrowable(_ => StatusCode.ERROR)
```

## Mapping Successful Values to a Status

A success mapper inspects the value produced by the effect. Use `StatusMapper.success` when you also want a description, or `StatusMapper.successNoDescription` when the status code is enough.

We mark `5xx` responses as errors with the response message as description, and every other response as `OK`:

```scala mdoc:silent
def successMapper: StatusMapper.Success[Response] =
  StatusMapper.success[Response] { response =>
    if (response.code >= 500) StatusCode.ERROR else StatusCode.OK
  } { response =>
    if (response.code >= 500) Some(response.message) else None
  }
```

The description is applied only when the status code is `ERROR`. For `OK` and `UNSET`, the second function's result is ignored.

## Combining Both Mappers

A span method takes a single `StatusMapper`, so we merge the failure and success mappers with `StatusMapper.both`:

```scala mdoc:silent
def statusMapper: StatusMapper[LookupError, Response] =
  StatusMapper.both(failureWithException, successMapper)
```

Now we use it on a span. The method form, `Tracing#span`, accepts the same `statusMapper` argument as the aspect:

```scala mdoc:compile-only
def tracedLookupWithBoth(id: String): ZIO[Tracing, LookupError, Response] =
  ZIO.serviceWithZIO[Tracing] { tracing =>
    import tracing.aspects._

    lookup(id) @@ span("lookup", statusMapper = statusMapper)
  }
```

With this mapper, the four inputs produce the following spans:

| Input        | Outcome                    | Span status         | Exception event |
|--------------|----------------------------|---------------------|-----------------|
| `"ok"`       | `Response(200, "ok")`      | `OK`                | No              |
| `"overload"` | `Response(503, ...)`       | `ERROR`, with message | No            |
| `"missing"`  | `NotFound`                 | `UNSET`             | No              |
| `"down"`     | `Unavailable(...)`         | `ERROR`             | Yes             |

## Verifying the Result

To check the mapping, run the traced code against an in-memory exporter and read the finished spans back. [How to Test Traced Code](test-traced-code.md) shows the setup, and `SpanData#getStatus` returns the `StatusData` whose `getStatusCode` and `getDescription` you can assert on.

## Going Further

Mappers are ordinary values, so define them once and share them across the code base. The same `statusMapper` parameter exists on `Tracing#root`, `Tracing#inSpan`, `Tracing#extractSpan` and `Tracing#spanScoped`, and on the corresponding aspects.
