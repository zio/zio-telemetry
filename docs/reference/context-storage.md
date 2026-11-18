---
id: context-storage
title: "ContextStorage"
---

`ContextStorage` is the service that holds the current OpenTelemetry `io.opentelemetry.context.Context` for the running code. `Tracing`, `Baggage`, `Meter` and the logging integration all read and write the current context through it, which is how a span started by `Tracing` becomes visible to a baggage entry or a log record created inside it.

The trait is sealed, and its implementations are private to the library, so you obtain an instance only through the layers `OpenTelemetry.contextZIO` and `OpenTelemetry.contextJVM`. Most applications never call `ContextStorage` directly; it matters when you integrate with Java code that works with `Context`, or when you write your own instrumentation helpers.

## Operations

All operations are defined on the `ContextStorage` trait and take an implicit `Trace` that is omitted below:

```scala
sealed trait ContextStorage {
  def get: UIO[Context]
  def set(context: Context): UIO[Unit]
  def getAndSet(context: Context): UIO[Context]
  def updateAndGet(f: Context => Context): UIO[Context]
  def locally[R, E, A](context: Context)(zio: ZIO[R, E, A]): ZIO[R, E, A]
  def locallyScoped(context: Context): ZIO[Scope, Nothing, Unit]
}
```

The operations behave as follows:

- `ContextStorage#get` returns the current context.
- `ContextStorage#set` replaces the current context.
- `ContextStorage#getAndSet` replaces the current context and returns the previous one.
- `ContextStorage#updateAndGet` applies a function to the current context, stores the result and returns it.
- `ContextStorage#locally` runs the given effect with the given context as the current one and restores the previous context when the effect finishes, whether it succeeds, fails or is interrupted.
- `ContextStorage#locallyScoped` sets the given context immediately and restores the previous one when the enclosing `Scope` closes.

The following example reads the current context, runs an effect under a modified one and observes that the original context is restored afterwards:

```scala mdoc:compile-only
import io.opentelemetry.context.{Context, ContextKey}
import zio._
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.context.ContextStorage

val requestId: ContextKey[String] = ContextKey.named[String]("request-id")

val program: URIO[ContextStorage, (Option[String], Option[String])] =
  ZIO.serviceWithZIO[ContextStorage] { storage =>
    for {
      current <- storage.get
      scoped   = current.`with`(requestId, "req-42")
      inside  <- storage.locally(scoped)(storage.get.map(ctx => Option(ctx.get(requestId))))
      after   <- storage.get.map(ctx => Option(ctx.get(requestId)))
    } yield (inside, after)
  }

val runnable: UIO[(Option[String], Option[String])] =
  program.provide(OpenTelemetry.contextZIO)
```

Here `inside` is `Some("req-42")` while `after` is `None`, because `locally` restored the original context.

## Implementations

### ContextStorage.fiberRef

The `OpenTelemetry.contextZIO` layer creates a `ContextStorage` backed by a `FiberRef[Context]` that starts at `Context.root()`. Each layer build creates a new `FiberRef`, so every `ContextStorage` obtained from a separate build holds its own context.

Because the context lives in a `FiberRef`, it follows the ZIO fiber model. A forked fiber inherits the context of its parent at the moment of the fork, changes made in a child do not leak into the parent unless the fibers are joined, and the context is unaffected by which thread runs the fiber. This is the default choice and the right one for applications that use only ZIO and the Java SDK.

### ContextStorage.native

The `OpenTelemetry.contextJVM` layer provides a `ContextStorage` that delegates to OpenTelemetry's own storage, `Context.current()` and `Context#makeCurrent`, which is backed by a `ThreadLocal`. The layer holds no state, so building it twice is harmless.

Each operation performs a single read or write of the thread-local on whatever thread executes it. It does not follow a fiber when ZIO moves it to another thread. This only gives sensible results when the OpenTelemetry Java agent is attached: the agent has ZIO support that carries the thread-local context along with fibers. The same agent instrumentation lets ZIO spans become children of spans created by the agent, such as incoming HTTP server spans.

## Choosing Between the Two

Pick `OpenTelemetry.contextZIO` unless you use the Java agent. It needs no agent, works with plain `OpenTelemetry.custom`, and behaves deterministically across fiber forks and thread hops. Pick `OpenTelemetry.contextJVM` when the agent instruments your application and you need ZIO code and agent-created spans to share one context; pair it with `OpenTelemetry.global` so that both sides use the same tracer provider.

Do not mix the two in one application: spans created by `Tracing` over one storage are invisible to code that reads the other. The logging layer also depends on the choice, because it reads the context from the logging fiber's `FiberRef` for `contextZIO` and from `Context.current()` for `contextJVM`.
See [OpenTelemetry Layers](opentelemetry-layers.md) for the layer signatures, [Context and Fibers](../concepts/context-and-fibers.md) for the background, and [How to Use the Java Agent](../how-to/use-the-java-agent.md) for the agent setup.
