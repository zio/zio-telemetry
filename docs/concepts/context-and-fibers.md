---
id: context-and-fibers
title: "Context and Fibers"
---

OpenTelemetry keeps track of the current span in an immutable `io.opentelemetry.context.Context`. A tracing library has to answer one question: where is the current context stored while the program runs? In plain Java the answer is a `ThreadLocal`. In ZIO the answer is more subtle, and the `ContextStorage` service exists to make the choice explicit.

## Why Threads Are Not Enough

ZIO runs many fibers on a small pool of threads, and a single fiber can move from one thread to another between two steps. A thread-local value therefore does not follow the logical flow of an effect. It follows whatever happens to run on the thread. Two concurrent requests handled by different fibers can read each other's context, and a request that hops threads can lose its own.

A `FiberRef` is ZIO's answer. Its value belongs to the fiber, is inherited by forked children, and is restored when a scoped modification ends. Storing the OpenTelemetry context in a `FiberRef` gives each fiber its own current span, independent of the threads underneath.

## Two Implementations

`ContextStorage` is a sealed abstraction over the two approaches, and the `OpenTelemetry` object offers one layer for each.

`OpenTelemetry.contextZIO` stores the context in a `FiberRef`. It is the default choice and works for any ZIO application. Its cost is that code outside of ZIO, which only knows `Context.current()`, cannot see the span.

`OpenTelemetry.contextJVM` stores the context in OpenTelemetry's own thread-local storage. Ordinary Java libraries see the current span, but the value follows threads rather than fibers. Use it only together with the OpenTelemetry Java agent, which instruments ZIO and keeps the thread-local value aligned with fibers.

## When to Use Each

Use `OpenTelemetry.contextZIO` when you configure the SDK in code with `OpenTelemetry.custom`, and you do not run the Java agent. Use `OpenTelemetry.contextJVM` together with `OpenTelemetry.global` when the agent is attached, so that spans created by the agent for HTTP servers, JDBC and similar libraries become the parents of the spans you create by hand, and the other way around.

The two choices are not interchangeable at runtime. Mixing a `FiberRef` storage with the agent leaves the agent's spans and yours in two unrelated contexts. See [Context Storage](../reference/context-storage.md) for the full API and [How to Use the Java Agent](../how-to/use-the-java-agent.md) for the set-up.

## Interplay with Non-ZIO Code

With `OpenTelemetry.contextZIO`, code that reads the thread-local context, such as a Java client library, cannot see your span unless you bridge it. `Tracing#scopedEffect` and its variants temporarily install the current ZIO span as the thread-local context while a block of non-ZIO code runs, so that such code can see it.
