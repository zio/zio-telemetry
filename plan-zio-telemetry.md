# Plan A: zio-telemetry Modifications

**Repository**: `zio/zio-telemetry` (existing SBT project)
**Purpose**: Add a new `modules/opentelemetry/agent/` module that provides the Scala-side integration points the Java agent will instrument.

## Prerequisite For

This plan is a **prerequisite** for [Plan B: zio-opentelemetry-java](plan-zio-opentelemetry-java.md). The Java agent instruments classes defined in this plan's agent module. Plan A Step 1 must be completed before Plan B can target those classes.

---

## Execution Order

| Step | Description | Blocks |
|------|-------------|--------|
| 1 | Create agent module + core classes | Plan B Step 5 (zio-telemetry integration) |
| 2 | Update Logger/LogFormats pattern matches | Plan B testing phases |
| 3 | Add layer constructors + supervisor | Plan B testing phases |

---

## Step 1: Agent Module — Core Classes

### 1.1 SBT Module Definition

**File**: `build.sbt` (add new project definition)

```scala
lazy val opentelemetryAgent = project
  .in(file("modules/opentelemetry/agent"))
  .settings(
    name := "zio-opentelemetry-agent",
    // reuse stdExampleSettings or equivalent from existing build
  )
  .dependsOn(opentelemetryCore)
```

Register in the root `aggregate` list.

**File**: `project/Dependencies.scala` (add dependency set)

```scala
lazy val opentelemetryAgent = Seq(
  "dev.zio" %% "zio" % zioVersion,
  "io.opentelemetry" % "opentelemetry-api" % openTelemetryVersion,
  "io.opentelemetry" % "opentelemetry-sdk" % openTelemetryVersion % Test,
  "io.opentelemetry" % "opentelemetry-sdk-testing" % openTelemetryVersion % Test
)
```

### 1.2 `FiberRefContextStorage`

**File**: `modules/opentelemetry/agent/src/main/scala/zio/telemetry/opentelemetry/agent/FiberRefContextStorage.scala`

A `ContextStorage` backed by `FiberRef[Context]`. Structurally identical to `ContextStorage.ZIOFiberRef` but lives in the agent module so it can be the target of the Java agent's bytecode instrumentation (the agent replaces the `FiberRef` with its own singleton connected to the ThreadLocalBridge).

```scala
package zio.telemetry.opentelemetry.agent

import io.opentelemetry.context.Context
import zio._
import zio.telemetry.opentelemetry.core.context.internal.ContextStorage

final class FiberRefContextStorage(
  private[opentelemetry] val ref: FiberRef[Context]
) extends ContextStorage {

  override def get(implicit trace: Trace): UIO[Context] =
    ref.get

  override def locally[R, E, A](ctx: Context)(zio: => ZIO[R, E, A])(implicit
    trace: Trace
  ): ZIO[R, E, A] =
    ref.locally(ctx)(zio)

  override def locallyScoped(ctx: Context)(implicit trace: Trace): ZIO[Scope, Nothing, Unit] =
    ref.locallyScoped(ctx)
}
```

### 1.3 `ZioAgentContext` — Agent Instrumentation Hook

**File**: `modules/opentelemetry/agent/src/main/scala/zio/telemetry/opentelemetry/agent/ZioAgentContext.scala`

The Java agent instruments `getAgentFiberRef()` at runtime to return its singleton `FiberRef[Context]`. Without the agent, this method returns `None`.

```scala
package zio.telemetry.opentelemetry.agent

import io.opentelemetry.context.Context
import zio._

object ZioAgentContext {

  /**
   * Returns the agent-provided FiberRef[Context], if the agent is attached.
   * Instrumented by the zio-opentelemetry-javaagent at runtime.
   * Without the agent, always returns None.
   */
  private[agent] def getAgentFiberRef(): Option[FiberRef[Context]] = None

  /**
   * Checks whether the OpenTelemetry Java agent with ZIO support is attached.
   */
  def isAgentAttached: Boolean = getAgentFiberRef().isDefined
}
```

**Why this design**: Mirrors otel4s's `IOLocalContextStorage$.getAgentLocalContext()` pattern. The Java agent's `ZioAgentContextInstrumentation` rewrites `getAgentFiberRef()` bytecode to return `Some(ZioContextSingleton.contextFiberRef)`.

### 1.4 `OpenTelemetry` Layer Constructor

**File**: `modules/opentelemetry/agent/src/main/scala/zio/telemetry/opentelemetry/agent/OpenTelemetry.scala`

Provides the public API for agent-aware integration.

```scala
package zio.telemetry.opentelemetry.agent

import io.opentelemetry.api.{OpenTelemetry => JOpenTelemetry}
import io.opentelemetry.context.Context
import zio._
import zio.telemetry.opentelemetry.core
import zio.telemetry.opentelemetry.core.context.ContextPropagator
import zio.telemetry.opentelemetry.core.context.internal.ContextStorage

object OpenTelemetry {

  /**
   * Creates an OpenTelemetry instance backed by the Java agent's FiberRef context storage.
   * Requires the zio-opentelemetry-javaagent to be attached.
   * Falls back to JavaOtelThreadLocal with a warning if the agent is not detected.
   */
  def global(logAnnotated: Boolean = false)(implicit trace: Trace): TaskLayer[core.OpenTelemetry] =
    ZLayer.scoped {
      for {
        underlying <- ZIO.attempt(io.opentelemetry.api.GlobalOpenTelemetry.get())
        propagator  = ContextPropagator.fromJava(underlying.getPropagators)
        storage    <- createStorage
      } yield core.OpenTelemetry.make(storage, underlying, propagator, logAnnotated)
    }

  private def createStorage(implicit trace: Trace): URIO[Scope, ContextStorage] =
    ZioAgentContext.getAgentFiberRef() match {
      case Some(fiberRef) =>
        ZIO.logInfo("zio-opentelemetry-agent: agent-provided FiberRef detected") *>
          ZIO.succeed(new FiberRefContextStorage(fiberRef))
      case None =>
        ZIO.logWarning("zio-opentelemetry-agent: agent not detected, falling back to JavaOtelThreadLocal") *>
          ZIO.succeed(ContextStorage.JavaOtelThreadLocal)
    }
}
```

### 1.5 ThreadLocalBridge Supervisor Installer

**File**: `modules/opentelemetry/agent/src/main/scala/zio/telemetry/opentelemetry/agent/ContextBridge.scala`

Installs a `Supervisor` that syncs the Java OTel ThreadLocal context with the ZIO `FiberRef` on every fiber suspend/resume. This is the core of the ThreadLocalBridge approach.

```scala
package zio.telemetry.opentelemetry.agent

import io.opentelemetry.context.Context
import zio._
import zio.internal.FiberRuntime

object ContextBridge {

  /**
   * Installs a supervisor that keeps the Java OTel ThreadLocal Context
   * in sync with the FiberRef-based context on every fiber transition.
   *
   * onSuspend: resets the ThreadLocal to Context.root() to prevent leaking
   *            fiber-local state to the thread when the fiber yields.
   * onResume:  sets the ThreadLocal to the resuming fiber's FiberRef value,
   *            restoring correct context.
   */
  def installSupervisor(fiberRef: FiberRef[Context]): ZIO[Scope, Nothing, Unit] = {
    val link: Context => Unit = { ctx =>
      ctx.makeCurrent()
      ()
    }

    val supervisor = new Supervisor[Unit] {
      override def value(implicit trace: Trace): UIO[Unit] = ZIO.unit

      override def unsafeOnStart[R, E, A](
        environment: ZEnvironment[R],
        effect: ZIO[R, E, A],
        parent: Option[Fiber.Runtime[Any, Any]],
        fiber: Fiber.Runtime[E, A]
      )(implicit unsafe: Unsafe): Unit = ()

      override def unsafeOnEnd[R, E, A](
        value: Exit[E, A],
        fiber: Fiber.Runtime[E, A]
      )(implicit unsafe: Unsafe): Unit = ()

      override def unsafeOnSuspend[E, A](
        fiber: Fiber.Runtime[E, A]
      )(implicit unsafe: Unsafe): Unit =
        link(Context.root())

      override def unsafeOnResume[E, A](
        fiber: Fiber.Runtime[E, A]
      )(implicit unsafe: Unsafe): Unit = {
        val ctx = fiber.asInstanceOf[FiberRuntime[E, A]].getFiberRef(fiberRef)
        link(ctx)
      }
    }

    Supervisor.addSupervisor(supervisor)
  }
}
```

**Why a supervisor**: Without it, when fiber A suspends and fiber B resumes on the same thread, the ThreadLocal still holds fiber A's context. The supervisor actively resets/restores the ThreadLocal on every transition, ensuring Java OTel instrumentation always sees the correct context.

### 1.6 Verify Compilation

Run: `sbt opentelemetryAgent/compile`

---

## Step 2: Update Logger/LogFormats Pattern Matches

### 2.1 Logger.scala

**File**: `modules/opentelemetry/core/src/main/scala/zio/telemetry/opentelemetry/core/logs/Logger.scala`

Find the `ctxStorage match` block (around line 50-55) and add a case for `FiberRefContextStorage`:

```scala
// Before:
ctxStorage match {
  case cs: ContextStorage.ZIOFiberRef             => fiberRefs.get(cs.ref).foreach(builder.setContext)
  case _: ContextStorage.JavaOtelThreadLocal.type => builder.setContext(Context.current())
}

// After:
ctxStorage match {
  case cs: ContextStorage.ZIOFiberRef     => fiberRefs.get(cs.ref).foreach(builder.setContext)
  case cs: agent.FiberRefContextStorage   => fiberRefs.get(cs.ref).foreach(builder.setContext)
  case _: ContextStorage.JavaOtelThreadLocal.type => builder.setContext(Context.current())
}
```

### 2.2 LogFormats.scala

**File**: `modules/opentelemetry/zio-logging/src/main/scala/zio/telemetry/opentelemetry/zio/logging/LogFormats.scala`

Find the `ctxStorage match` block (around line 47-49) and add the same case:

```scala
// Before:
val maybeOtelContext = ctxStorage match {
  case cs: ContextStorage.ZIOFiberRef     => fiberRefs.get(cs.ref)
  case ContextStorage.JavaOtelThreadLocal => Some(Context.current())
}

// After:
val maybeOtelContext = ctxStorage match {
  case cs: ContextStorage.ZIOFiberRef     => fiberRefs.get(cs.ref)
  case cs: agent.FiberRefContextStorage   => fiberRefs.get(cs.ref)
  case ContextStorage.JavaOtelThreadLocal => Some(Context.current())
}
```

### 2.3 Verify

Run: `sbt opentelemetryCore/compile opentelemetryZioLogging/compile`

---

## Step 3: Add Public API Layer Constructors

### 3.1 Public API Object

**File**: `modules/opentelemetry/main/src/main/scala/zio/telemetry/opentelemetry/OpenTelemetry.scala`

Add a new layer constructor alongside the existing `global()`:

```scala
/**
 * Creates an OpenTelemetry instance that uses the Java agent's FiberRef-backed context storage.
 * Requires the zio-opentelemetry-javaagent to be attached at JVM startup.
 *
 * This is the recommended constructor when using the ZIO-aware OpenTelemetry Java agent.
 * It provides correct context propagation across fiber boundaries (fork, race, timeout, etc.)
 * unlike `global()` which uses ThreadLocal-based storage that does not propagate across fibers.
 */
def agent(logAnnotated: Boolean = false)(implicit trace: Trace): TaskLayer[core.OpenTelemetry] =
  agent.OpenTelemetry.global(logAnnotated)
```

This delegates to the agent module's `OpenTelemetry.global()`, providing a single entry point for users: `OpenTelemetry.agent()`.

### 3.2 Verify Full Build

Run: `sbt compile`

---

## File Summary

| File | Action | Description |
|------|--------|-------------|
| `build.sbt` | Modify | Add `opentelemetryAgent` project |
| `project/Dependencies.scala` | Modify | Add `opentelemetryAgent` dependency set |
| `modules/opentelemetry/agent/src/main/scala/.../FiberRefContextStorage.scala` | Create | New ContextStorage backed by FiberRef |
| `modules/opentelemetry/agent/src/main/scala/.../ZioAgentContext.scala` | Create | Agent instrumentation hook point |
| `modules/opentelemetry/agent/src/main/scala/.../OpenTelemetry.scala` | Create | Agent-aware layer constructors |
| `modules/opentelemetry/agent/src/main/scala/.../ContextBridge.scala` | Create | ThreadLocalBridge supervisor installer |
| `modules/opentelemetry/core/src/main/scala/.../Logger.scala` | Modify | Add FiberRefContextStorage pattern match case |
| `modules/opentelemetry/zio-logging/src/main/scala/.../LogFormats.scala` | Modify | Add FiberRefContextStorage pattern match case |
| `modules/opentelemetry/main/src/main/scala/.../OpenTelemetry.scala` | Modify | Add `agent()` layer constructor |

---

## Key Reference Files

| What | Path |
|------|------|
| Existing ContextStorage trait | `modules/opentelemetry/core/src/main/scala/zio/telemetry/opentelemetry/core/context/internal/ContextStorage.scala` |
| Existing ZIOFiberRef implementation | Same file, lines 23-36 |
| Existing JavaOtelThreadLocal | Same file, lines 41-63 |
| Existing OpenTelemetry.global() | `modules/opentelemetry/main/src/main/scala/zio/telemetry/opentelemetry/OpenTelemetry.scala`, lines 28-34 |
| Existing autoinstrumented | `modules/opentelemetry/core/src/main/scala/zio/telemetry/opentelemetry/core/OpenTelemetry.scala`, line 150 |
| Logger pattern match | `modules/opentelemetry/core/src/main/scala/zio/telemetry/opentelemetry/core/logs/Logger.scala`, lines 50-55 |
| LogFormats pattern match | `modules/opentelemetry/zio-logging/src/main/scala/zio/telemetry/opentelemetry/zio/logging/LogFormats.scala`, lines 47-49 |
| Known #1069 reference | `modules/opentelemetry/main/src/test/scala/.../InterruptionTracerTest.scala`, line 118 |
| otel4s equivalent: IOLocalContextStorage | `otel4s/oteljava/context-storage/.../IOLocalContextStorage.scala` |
| otel4s equivalent: getAgentLocalContext hook | Same file, `getAgentLocalContext()` method |

---

## What the Java Agent Will Instrument

The [Plan B](plan-zio-opentelemetry-java.md) agent targets these classes from this plan:

1. **`ZioAgentContext$.getAgentFiberRef()`** — rewritten to return `Some(ZioContextSingleton.contextFiberRef)` instead of `None`
2. **`FiberRefContextStorage`** — used as the active `ContextStorage` when agent is attached (replaces `JavaOtelThreadLocal`)
3. **`ContextBridge.installSupervisor`** — may be called by `ZioRuntimeInstrumentation` to install the supervisor automatically
