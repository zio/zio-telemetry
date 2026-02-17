# LogSpanner: Bridging ZIO.logSpan with OpenTelemetry Spans

## Context

This document describes the **LogSpanner** feature developed as a contribution to the
[zio-telemetry](https://github.com/zio/zio-telemetry) open source project, implementing
the design proposed in [issue #1022](https://github.com/zio/zio-telemetry/issues/1022).

### The Problem

ZIO has a built-in `ZIO.logSpan("name") { effect }` that wraps effects with named, timed
spans visible in log output. These are purely log-based: a string and a start time on a
`FiberRef` stack, rendered via `LogFormat.spans`. They have no span ID, no parent-child
linking, and no export to OpenTelemetry.

OpenTelemetry spans, on the other hand, are first-class objects with `traceId`, `spanId`,
`parentSpanId`, attributes, events, and status — exportable to Jaeger, Tempo, Datadog, etc.
for distributed trace visualization.

Today, if library code wants to add span instrumentation, it has two bad choices:

1. **Use `ZIO.logSpan`** — zero dependency overhead, but the spans are invisible to OTEL
   exporters and have no parent-child relationship tracking.
2. **Depend on `Tracer` from zio-telemetry** — creates real OTEL spans, but introduces a
   viral dependency: every module that marks spans must depend on zio-telemetry and have
   `Tracer` in its environment.

### The Solution: LogSpanner

LogSpanner is a **pluggable span dispatch mechanism** stored in a `FiberRef`. By default it
delegates to `ZIO.logSpan` (zero OTEL overhead). When an OTEL-backed implementation is
installed at the application edge, the same call sites create real OpenTelemetry spans
instead.

This mirrors how ZIO itself handles logging and metrics — a pluggable backend installed at
the runtime level, invisible to library code.

---

## How It Works

### The Trait

```scala
trait LogSpanner {
  def logSpan[R, E, A](name: String, effect: ZIO[R, E, A]): ZIO[R, E, A]
}
```

A single method: wrap an effect with a named span. The semantics depend entirely on the
installed backend.

### The FiberRef

```scala
object LogSpanner {

  val default: LogSpanner = new LogSpanner {
    override def logSpan[R, E, A](name: String, effect: ZIO[R, E, A]): ZIO[R, E, A] =
      ZIO.logSpan(name)(effect)
  }

  private[opentelemetry] val currentLogSpanner: FiberRef[LogSpanner] =
    Unsafe.unsafe { implicit unsafe =>
      FiberRef.unsafe.make[LogSpanner](LogSpanner.default)
    }
}
```

The `FiberRef` is initialized using `Unsafe.unsafe { FiberRef.unsafe.make }` — the same
pattern ZIO core uses for `FiberRef.currentLogSpan`. This makes the `FiberRef` available at
module initialization time without requiring a ZIO runtime. The default value delegates to
`ZIO.logSpan`, so the feature is backwards-compatible with zero effort.

Because it is a `FiberRef`, the installed backend automatically propagates to child fibers
(including `fork`, `timeout`, `race`, etc.) and is correctly scoped.

### The `@@` Aspect

```scala
def span(spanName: String): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
  new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
    override def apply[R, E, A](effect: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
      currentLogSpanner.getWith(_.logSpan(spanName, effect))
  }
```

This is the primary call-site API. Library code writes:

```scala
myEffect @@ LogSpanner.span("operationName")
```

At runtime, `getWith` reads the current `FiberRef[LogSpanner]` value and dispatches to
whichever backend is installed. No `Tracer` in the environment. No OTEL dependency at the
call site.

### Scoped Installation

```scala
def installLogSpanner(logSpanner: LogSpanner): ZIO[Scope, Nothing, Unit] =
  currentLogSpanner.locallyScoped(logSpanner)
```

Installation is scoped — when the scope closes, the previous `LogSpanner` is restored.
This is the mechanism for activating an OTEL backend at the application edge.

---

## Three Backends

### 1. Default (ZIO logSpan only)

```scala
LogSpanner.default
```

Delegates to `ZIO.logSpan`. Zero overhead. Spans visible in ZIO log output via
`LogFormat.spans`. This is what you get if you never install anything.

### 2. OTEL-only

```scala
OtelLogSpanner.make(tracer: Tracer): LogSpanner
```

Creates real OpenTelemetry spans via `Tracer.span`. Does NOT call `ZIO.logSpan` — span
information is only visible through OTEL exporters. Parent-child nesting is handled
automatically by the OTEL `ContextStorage`.

### 3. Hybrid (OTEL + ZIO logSpan)

```scala
OtelLogSpanner.makeHybrid(tracer: Tracer): LogSpanner
```

Creates both an OTEL span AND a ZIO `logSpan`. Dual visibility: OTEL spans for distributed
tracing exporters, and ZIO logSpans for local development log output.

This is recommended during migration or for local development where you want spans in both
the console log and the trace exporter.

---

## Usage Patterns

### Library Code (No OTEL dependency needed)

```scala
// In a database module, service module, etc.
// Depends only on zio-core.
import zio.telemetry.opentelemetry.core.trace.LogSpanner

def findPersonById(id: PersonId): Task[Option[Person]] =
  repository.findById(id) @@ LogSpanner.span("repo:findById:Person")

def createEvent(input: CreateEventInput): Task[Event] =
  (for {
    validated <- validate(input) @@ LogSpanner.span("validate")
    saved     <- repository.create(validated) @@ LogSpanner.span("persist")
  } yield saved) @@ LogSpanner.span("createEvent")
```

### Application Bootstrap (OTEL backend installed at the edge)

```scala
// In main application wiring — this is the only place that touches Tracer.
import zio.telemetry.opentelemetry.core.trace.OtelLogSpanner

val program = myApp.provide(
  // ... other layers ...
  tracerLayer,
  OtelLogSpanner.layer       // or OtelLogSpanner.hybridLayer
)
```

Or manually:

```scala
ZIO.scoped {
  for {
    tracer <- ZIO.service[Tracer]
    _      <- LogSpanner.installLogSpanner(OtelLogSpanner.makeHybrid(tracer))
    _      <- runApplication
  } yield ()
}
```

### ZLayer Convenience

```scala
// OTEL-only: spans go to exporter, not to ZIO log output
OtelLogSpanner.layer: ZLayer[Tracer, Nothing, Unit]

// Hybrid: spans go to both exporter AND ZIO log output
OtelLogSpanner.hybridLayer: ZLayer[Tracer, Nothing, Unit]
```

These layers are scoped — the installation reverts when the layer's scope closes.

---

## Interaction with ZIO.logAnnotate

The zio-telemetry maintainer (@grouzen) identified that `ZIO.logAnnotate` can serve as a
transport for span attributes. When the `Tracer` is constructed with `logAnnotated = true`,
log annotations are automatically copied to OTEL span attributes:

```scala
ZIO.logAnnotate("userId", "42") {
  myEffect @@ LogSpanner.span("processRequest")
}
// With logAnnotated=true, the OTEL span "processRequest"
// will have attribute userId="42"
```

This makes `ZIO.logAnnotate` + `LogSpanner.span` a complete replacement for
`tracer.span(name, attributes = ...)` in many cases, using only ZIO-native APIs at the
call site.

---

## Testing

### Test Infrastructure

Tests use the **TracerTestkit** from zio-telemetry's testkit module, which provides an
in-memory span exporter:

```scala
// Provides TracerTestkit backed by an in-memory span exporter
TracerTestkit.inMemory: RLayer[ContextStorage, TracerTestkit]

// Provides ContextStorage backed by a ZIO FiberRef (fiber-correct)
OpenTelemetryTestkit.ctxStorageZioFiberRef: ULayer[ContextStorage]
```

The testkit's `getFinishedSpans` method returns all exported spans as `SpanData` case
classes with `name`, `spanId`, `parentSpanId`, `status`, `attributes`, etc.

### Test Suites

The `LogSpannerTest` spec covers five areas:

#### 1. Default Backend

Verifies that when no OTEL backend is installed, `LogSpanner.span` delegates to
`ZIO.logSpan` and creates zero OTEL spans:

```scala
test("delegates to ZIO.logSpan — no OTEL span created") {
  for {
    tracerTestkit <- ZIO.service[TracerTestkit]
    _             <- ZIO.unit @@ LogSpanner.span("mySpan")
    spans         <- tracerTestkit.getFinishedSpans
  } yield assert(spans)(isEmpty)
}
```

Also verifies the span name IS visible in `FiberRef.currentLogSpan`:

```scala
test("ZIO logSpan name is visible in log annotations") {
  for {
    ref    <- Ref.make(List.empty[String])
    _      <- FiberRef.currentLogSpan.getWith { spans =>
                ref.set(spans.map(_.label))
              } @@ LogSpanner.span("mySpan")
    labels <- ref.get
  } yield assert(labels)(contains("mySpan"))
}
```

#### 2. OTEL Backend

Verifies that with an OTEL backend installed, `LogSpanner.span` creates real OTEL spans
with correct parent-child nesting:

```scala
test("OTEL span has correct parent-child nesting") {
  for {
    tracerTestkit <- ZIO.service[TracerTestkit]
    tracer        <- tracerTestkit.getTracer(instrumentationScopeName)
    _             <- ZIO.scoped[Any] {
                       LogSpanner.installLogSpanner(OtelLogSpanner.make(tracer)) *>
                         (ZIO.unit @@ LogSpanner.span("child")
                                   @@ LogSpanner.span("parent"))
                     }
    spans         <- tracerTestkit.getFinishedSpans
    parent         = spans.find(_.name == "parent")
    child          = spans.find(_.name == "child")
  } yield assert(parent)(isSome(anything)) &&
    assert(child)(isSome(assertSpanParentId(equalTo(parent.get.spanId))))
}
```

Also verifies interop with `tracer.span` (LogSpanner spans correctly nest as children of
tracer-created spans) and `ZIO.logAnnotate` attribute propagation.

#### 3. Hybrid Backend

Verifies that the hybrid backend creates both an OTEL span AND a ZIO logSpan:

```scala
test("creates both OTEL span and ZIO logSpan") {
  // ... install hybrid backend ...
  // Assert: OTEL span exists in exporter
  assert(hybridSpan)(isSome(anything)) &&
  // Assert: ZIO logSpan visible in FiberRef.currentLogSpan
  assert(logLabels)(contains("hybridSpan"))
}
```

#### 4. Scoping

Verifies scoped installation and reversion:

- After scope close, spans revert to the previous backend (OTEL span "inside" exists,
  "outside" does not).
- Nested scopes override correctly (inner default produces no OTEL span, outer OTEL resumes
  after inner scope closes).
- `OtelLogSpanner.layer` installs correctly via `ZLayer`.

#### 5. Fiber Correctness

Verifies that the `FiberRef`-based approach correctly propagates through ZIO's concurrency
primitives:

- **Fork**: `LogSpanner` propagates through `fork` — child fiber inherits the backend.
- **Timeout (non-timeout case)**: Spans survive `timeout` when the effect completes in time.
- **Timeout (interrupted case)**: When an effect is interrupted by `timeout`, the OTEL span
  is still recorded with `StatusCode.ERROR`.
- **Child fiber inheritance**: Backend installed on the parent fiber is visible to spans
  created on forked child fibers.
- **Hybrid on timeout**: Both OTEL and ZIO logSpan are visible even through concurrent
  combinators.

---

## Possible Migration to ZIO Core

The `LogSpanner` FiberRef-based dispatch is structurally identical to patterns that already
exist in ZIO core:

| Concern     | ZIO Core Mechanism          | Analogy                          |
|-------------|-----------------------------|----------------------------------|
| Logging     | `ZLogger` (pluggable)       | `LogSpanner` for spans           |
| Metrics     | `MetricListener` (pluggable)| `LogSpanner` for spans           |
| Spans       | `FiberRef.currentLogSpan`   | `LogSpanner.currentLogSpanner`   |

ZIO's `logSpan` implementation today pushes a `LogSpan(label, startTime)` onto the
`FiberRef.currentLogSpan` stack. If ZIO core added a `SpanListener` hook (analogous to
`ZLogger` for logs and `MetricListener` for metrics), then `ZIO.logSpan` could natively
dispatch to OTEL without any call-site changes:

```
ZIO.logSpan("name") { effect }
   |
   v
SpanListener.onSpanStart("name")  // registered by zio-telemetry at bootstrap
   |
   v
OTEL Tracer.span("name") { ... }  // creates real OTEL span
```

**Advantage**: Zero call-site migration. Existing code using `ZIO.logSpan` would
automatically produce OTEL spans when a `SpanListener` is registered.

**Trade-off**: This requires an RFC and acceptance into ZIO core, which has a higher
organizational bar than a library contribution. The `LogSpanner` approach in zio-telemetry
achieves the same goal with a small call-site change (`ZIO.logSpan("name") { e }` becomes
`e @@ LogSpanner.span("name")`), and is mergeable with only the zio-telemetry maintainer's
approval.

Both paths are viable. The zio-telemetry contribution is the pragmatic first step; a ZIO
core RFC could follow once the pattern is proven in production.

---

## Relationship to Other Issues

### Issue #1069 — Context Loss on Fiber Operations

A separate issue where OTEL context is lost after `timeout`, `race`, or `fork` due to
the ThreadLocal/FiberRef mismatch. LogSpanner sidesteps this for the common case by using
`FiberRef` (which inherits to child fibers) rather than Java's `ThreadLocal`. Two sub-fixes:

- **Sub-problem A** (.onExit fix): ~10 lines, ensures interrupted spans are recorded. Small
  PR, invited by maintainer.
- **Sub-problem B** (FiberRef.asThreadLocal bridge): Major effort, only affects
  `OpenTelemetry.global` (Java agent mode). Not needed for `OpenTelemetry.custom`.

### Issue #5303 — OpenTelemetry Java Spec Limitation

The upstream reason why ZIO + OTEL ThreadLocal is fundamentally broken: the OTEL spec
requires scopes to close in LIFO order, but fiber scheduling doesn't guarantee this.
`ContextStorage.ZIOFiberRef` solves this by storing context in ZIO's fiber-local storage
instead of Java's `ThreadLocal`.

---

## File Locations

### Implementation (in zio-telemetry fork)

| File | Purpose |
|------|---------|
| `modules/opentelemetry/core/.../trace/LogSpanner.scala` | Trait, FiberRef, `@@` aspect |
| `modules/opentelemetry/core/.../trace/OtelLogSpanner.scala` | OTEL + hybrid backends, layers |

### Tests

| File | Purpose |
|------|---------|
| `modules/opentelemetry/main/.../trace/LogSpannerTest.scala` | 15 test cases across 5 suites |

### Testkit (existing, used by tests)

| File | Purpose |
|------|---------|
| `modules/opentelemetry/testkit/.../trace/TracerTestkit.scala` | In-memory span exporter |
| `modules/opentelemetry/testkit/.../trace/SpanData.scala` | Span data model for assertions |
| `modules/opentelemetry/testkit/.../OpenTelemetryTestkit.scala` | ContextStorage layer factories |

---

## Summary

LogSpanner is a small, focused abstraction (~90 lines of implementation + ~75 lines of OTEL
backend) that solves a real gap in the ZIO observability story. It lets library authors add
span instrumentation without depending on any tracing library, while application authors get
real OTEL spans by installing a backend at bootstrap.

The pattern follows ZIO's own conventions (`Unsafe.unsafe { FiberRef.unsafe.make }`,
scoped installation, `ZIOAspect`), is fiber-correct by construction (FiberRef propagation),
and has been validated through 15 test cases covering default/OTEL/hybrid backends, scoping,
and fiber correctness.
