# Plan B: zio-opentelemetry-java

**Repository**: New Gradle project (`zio-opentelemetry-java/`)
**Purpose**: A custom OpenTelemetry Java agent distribution with ZIO fiber-aware context propagation, mirroring the architecture of `otel4s-opentelemetry-java` but targeting ZIO instead of Cats Effect.

## Prerequisites

This plan **depends on** [Plan A: zio-telemetry Modifications](plan-zio-telemetry.md).

| Plan B Step | Requires Plan A |
|-------------|----------------|
| Steps 1-4 (build infra, bootstrap, singleton, bridge) | None — can start immediately |
| Step 5 (zio-telemetry integration module) | Plan A Step 1 complete (`ZioAgentContext$` class must exist) |
| Steps 6-7 (tests) | Plan A Steps 1-2 complete |
| Step 8 (smoke tests) | Plan A fully complete |

---

## Project Structure

```
zio-opentelemetry-java/
├── agent/                                        # Final agent JAR assembly
├── bootstrap/                                    # Bootstrap classloader classes
│   └── src/main/java/.../zio/v2_1/
│       └── FiberLocalContextHelper.java
├── custom/                                       # Distribution resource provider
├── instrumentation/
│   ├── zio-common-2.1/                          # Shared: ZioContextSingleton
│   ├── zio-2.1/                                 # Core ZIO instrumentation
│   └── zio-telemetry-agent-4.0/                 # zio-telemetry integration
├── opentelemetry-api-shaded-for-instrumenting/   # Shaded OTel API (application.* namespace)
├── testing/
│   └── agent-for-testing/                       # Test agent JAR assembly
├── smoke-tests/                                 # Docker-based smoke tests
├── smoke-tests-images/
│   └── zio-http/                                # ZIO HTTP sample app
├── gradle/
│   ├── instrumentation.gradle
│   └── shadow.gradle
├── build.gradle
├── settings.gradle
└── gradle.properties
```

---

## Step 1: Build Infrastructure

### 1.1 Root `build.gradle`

**Reference**: `otel4s-opentelemetry-java/build.gradle`

```groovy
plugins {
  id "com.diffplug.spotless" version "7.0.3"
}

group = 'dev.zio'
version = '2.28.1'  // match upstream OTel agent version

ext {
  otelSdkVersion = '1.62.0'
  otelAgentVersion = '2.28.1'
  zioVersion = '2.1.25'
  scalaVersion = '2.13'
  scalaFullVersion = '2.13.16'
}

allprojects {
  java {
    toolchain { languageVersion = JavaLanguageVersion.of(8) }
  }
}

spotless {
  java { googleJavaFormat() }
}
```

### 1.2 `settings.gradle`

```groovy
rootProject.name = 'zio-opentelemetry-java'
include ':opentelemetry-api-shaded-for-instrumenting'
include ':agent'
include ':bootstrap'
include ':custom'
include ':instrumentation:zio-common-2.1'
include ':instrumentation:zio-2.1'
include ':instrumentation:zio-telemetry-agent-4.0'
include ':smoke-tests-images:zio-http'
include ':smoke-tests'
include ':testing:agent-for-testing'
```

### 1.3 `opentelemetry-api-shaded-for-instrumenting/build.gradle`

Shades `io.opentelemetry.context` → `application.io.opentelemetry.context` so instrumentation code can reference the application's (unshaded) OTel API.

**Reference**: `otel4s-opentelemetry-java/opentelemetry-api-shaded-for-instrumenting/build.gradle`

```groovy
plugins {
  id "java"
  id "com.gradleup.shadow"
}

dependencies {
  implementation "io.opentelemetry:opentelemetry-api:${otelSdkVersion}"
  implementation "io.opentelemetry:opentelemetry-context:${otelSdkVersion}"
}

shadowJar {
  relocate("io.opentelemetry", "application.io.opentelemetry") {
    include "io.opentelemetry.api.**"
    include "io.opentelemetry.context.**"
  }
}
```

### 1.4 `gradle/shadow.gradle`

Package relocation rules for the agent assembly:
- Agent's internal OTel → shaded to `io.opentelemetry.javaagent.shaded.*`
- `application.io.opentelemetry` → reverse-shaded back to `io.opentelemetry` in final JAR

**Reference**: `otel4s-opentelemetry-java/gradle/shadow.gradle`

### 1.5 `gradle/instrumentation.gradle`

Shared build logic for instrumentation modules:
- Applies `muzzle-generation` and `muzzle-check` plugins for ZIO version compatibility validation
- Configures test JVM with `-javaagent:agent-for-testing.jar`
- Enables context leak detection: `otel.javaagent.testing.context-leak-detection=true`
- Sets `zio.trackFiberContext=true` system property for tests

**Reference**: `otel4s-opentelemetry-java/gradle/instrumentation.gradle`

### 1.6 Verify

Run: `./gradlew build` (should produce empty builds for subprojects with no sources yet)

---

## Step 2: Bootstrap — `FiberLocalContextHelper`

**File**: `bootstrap/src/main/java/io/opentelemetry/javaagent/bootstrap/zio/v2_1/FiberLocalContextHelper.java`

Lives in the bootstrap classloader — accessible from both the agent and application classloaders. Stores a reference to the bridged `ThreadLocal<Context>` and provides the routing logic.

**Reference**: `otel4s-opentelemetry-java/bootstrap/.../FiberLocalContextHelper.java`

```java
package io.opentelemetry.javaagent.bootstrap.zio.v2_1;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Logger;

public final class FiberLocalContextHelper {

  private static final Logger logger = Logger.getLogger(FiberLocalContextHelper.class.getName());

  private static final AtomicReference<ThreadLocal<Context>> fiberContextThreadLocal =
      new AtomicReference<>();

  private static final AtomicReference<Supplier<Boolean>> isUnderFiberContextSupplier =
      new AtomicReference<>(() -> false);

  public static void initialize(
      ThreadLocal<Context> fiberThreadLocal, Supplier<Boolean> isUnderFiberContext) {
    if (fiberContextThreadLocal.compareAndSet(null, fiberThreadLocal)) {
      isUnderFiberContextSupplier.set(isUnderFiberContext);
      logger.fine("The ZIO fiberThreadLocalContext is configured");
    } else {
      if (!fiberContextThreadLocal.get().equals(fiberThreadLocal)) {
        logger.warning(
            "The ZIO fiberThreadLocalContext is already configured. Ignoring subsequent calls.");
      }
    }
  }

  public static Boolean isUnderFiberContext() {
    return isUnderFiberContextSupplier.get().get();
  }

  public static Context current() {
    ThreadLocal<Context> local = fiberContextThreadLocal.get();
    return local != null ? local.get() : null;
  }

  public static Scope attach(Context toAttach) {
    ThreadLocal<Context> local = fiberContextThreadLocal.get();
    if (toAttach == null || local == null) {
      return Scope.noop();
    }
    Context beforeAttach = current();
    if (toAttach == beforeAttach) {
      return Scope.noop();
    }
    local.set(toAttach);
    return new ScopeImpl(beforeAttach, toAttach);
  }

  private static class ScopeImpl implements Scope {
    private final Context beforeAttach;
    private final Context toAttach;
    private boolean closed;

    ScopeImpl(Context beforeAttach, Context toAttach) {
      this.beforeAttach = beforeAttach;
      this.toAttach = toAttach;
    }

    @Override
    public void close() {
      if (!closed && current() == toAttach) {
        closed = true;
        fiberContextThreadLocal.get().set(beforeAttach);
      } else {
        logger.fine("Trying to close scope which does not represent current context. Ignoring.");
      }
    }
  }

  private FiberLocalContextHelper() {}
}
```

**File**: `bootstrap/build.gradle`

```groovy
plugins { id "java" }
dependencies {
  compileOnly "io.opentelemetry:opentelemetry-api:${otelSdkVersion}"
  compileOnly "io.opentelemetry:opentelemetry-context:${otelSdkVersion}"
}
```

---

## Step 3: Common — `ZioContextSingleton`

**File**: `instrumentation/zio-common-2.1/src/main/java/.../ZioContextSingleton.java`

Creates the singleton `FiberRef[Context]` and its ThreadLocal view. This is the shared state between the agent and the application.

**Reference**: `otel4s-opentelemetry-java/instrumentation/cats-effect-common-3.6/.../IoLocalContextSingleton.java`

```java
package io.opentelemetry.javaagent.instrumentation.zio.common.v2_1;

import application.io.opentelemetry.context.Context;
import io.opentelemetry.javaagent.tooling.muzzle.AgentTooling;

public class ZioContextSingleton {

  // Singleton FiberRef[application.io.opentelemetry.context.Context]
  // Created at class-load time using ZIO's Unsafe API
  public static final Object contextFiberRef;

  // ThreadLocal view of the FiberRef (delegates to fiber.getFiberRef when inside a fiber)
  public static final ThreadLocal<Context> fiberRefThreadLocal;

  // Bridging ThreadLocal: converts between agent (shaded) and application (unshaded) Context
  public static final ThreadLocal<io.opentelemetry.context.Context> contextThreadLocal;

  static {
    try {
      // Use reflection to call ZIO's FiberRef.unsafeMake / asThreadLocal
      // This avoids a direct compile-time dependency on ZIO internals
      Class<?> unsafeClass = Class.forName("zio.Unsafe");
      Class<?> fiberRefClass = Class.forName("zio.FiberRef");
      // ... reflective initialization ...

      Object unsafe = unsafeClass.getMethod("unsafe", scala.Function0.class).invoke(null);
      contextFiberRef = fiberRefClass.getMethod("unsafeMake", Object.class, unsafeClass)
          .invoke(null, Context.root(), unsafe);
      fiberRefThreadLocal = (ThreadLocal<Context>) fiberRefClass.getMethod("asThreadLocal",
          Class.forName("zio.Trace"), unsafeClass)
          .invoke(contextFiberRef, /* trace */ null, unsafe);

      contextThreadLocal = new ThreadLocal<io.opentelemetry.context.Context>() {
        @Override
        public io.opentelemetry.context.Context get() {
          Context appCtx = fiberRefThreadLocal.get();
          return AgentContextStorage.getAgentContext(appCtx);
        }

        @Override
        public void set(io.opentelemetry.context.Context value) {
          Context appCtx = AgentContextStorage.toApplicationContext(value);
          if (value == null) {
            fiberRefThreadLocal.remove();
          } else {
            fiberRefThreadLocal.set(appCtx);
          }
        }
      };
    } catch (Exception e) {
      throw new RuntimeException("Failed to initialize ZioContextSingleton", e);
    }
  }
}
```

**File**: `instrumentation/zio-common-2.1/build.gradle`

```groovy
plugins { id "java" }
apply from: "$rootDir/gradle/instrumentation.gradle"

dependencies {
  implementation project(':opentelemetry-api-shaded-for-instrumenting')
  implementation project(':bootstrap')
  compileOnly "dev.zio:zio_${scalaVersion}:${zioVersion}"
}
```

---

## Step 4: Core ZIO Instrumentation

### 4.1 `FiberContextBridge`

**File**: `instrumentation/zio-2.1/src/main/java/.../FiberContextBridge.java`

Wraps the agent's default `ContextStorage`. When inside a ZIO fiber, routes to `FiberLocalContextHelper`; otherwise delegates to the original storage.

**Reference**: `otel4s-opentelemetry-java/instrumentation/cats-effect-3.6/.../FiberContextBridge.java`

```java
package io.opentelemetry.javaagent.instrumentation.zio.v2_1;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextStorage;
import io.opentelemetry.context.Scope;
import io.opentelemetry.javaagent.bootstrap.zio.v2_1.FiberLocalContextHelper;

public class FiberContextBridge implements ContextStorage {

  private final ContextStorage agentContextStorage;

  public FiberContextBridge(ContextStorage delegate) {
    this.agentContextStorage = delegate;
  }

  @Override
  public Scope attach(Context toAttach) {
    if (FiberLocalContextHelper.isUnderFiberContext()) {
      return FiberLocalContextHelper.attach(toAttach);
    } else {
      return agentContextStorage.attach(toAttach);
    }
  }

  @Override
  public Context current() {
    if (FiberLocalContextHelper.isUnderFiberContext()) {
      return FiberLocalContextHelper.current();
    } else {
      return agentContextStorage.current();
    }
  }
}
```

### 4.2 `FiberContextBridgeInstaller`

**File**: `instrumentation/zio-2.1/src/main/java/.../FiberContextBridgeInstaller.java`

SPI-discovered hook that installs the bridge before the agent initializes.

**Reference**: `otel4s-opentelemetry-java/instrumentation/cats-effect-3.6/.../FiberContextBridgeInstaller.java`

```java
package io.opentelemetry.javaagent.instrumentation.zio.v2_1;

import com.google.auto.service.AutoService;
import io.opentelemetry.context.ContextStorage;
import io.opentelemetry.javaagent.tooling.BeforeAgentListener;

@AutoService(BeforeAgentListener.class)
public class FiberContextBridgeInstaller implements BeforeAgentListener {
  @Override
  public void beforeAgent() {
    ContextStorage.addWrapper(FiberContextBridge::new);
  }
}
```

### 4.3 `ZioRuntimeInstrumentation`

**File**: `instrumentation/zio-2.1/src/main/java/.../ZioRuntimeInstrumentation.java`

Instruments ZIO Runtime initialization to register the bridging `ThreadLocal` with the bootstrap helper and enable `RuntimeFlag.CurrentFiber`.

**Reference**: `otel4s-opentelemetry-java/instrumentation/cats-effect-3.6/.../IoRuntimeInstrumentation.java`

```java
package io.opentelemetry.javaagent.instrumentation.zio.v2_1;

import static net.bytebuddy.matcher.ElementMatchers.*;

import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import io.opentelemetry.javaagent.instrumentation.zio.common.v2_1.ZioContextSingleton;
import io.opentelemetry.javaagent.bootstrap.zio.v2_1.FiberLocalContextHelper;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class ZioRuntimeInstrumentation implements TypeInstrumentation {

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("zio.Runtime$");
  }

  @Override
  public void transform(TypeTransformer transformer) {
    transformer.applyAdviceToMethod(
        named("default").or(named("apply")),
        this.getClass().getName() + "$RuntimeAdvice");
  }

  @SuppressWarnings("unused")
  public static final class RuntimeAdvice {
    private RuntimeAdvice() {}

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit() {
      FiberLocalContextHelper.initialize(
          ZioContextSingleton.contextThreadLocal,
          () -> {
            // Check if we're inside a ZIO fiber
            ThreadLocal<?> currentFiber = zio.Fiber._currentFiber();
            return currentFiber != null && currentFiber.get() != null;
          });
    }
  }
}
```

**Note**: The exact target class/method needs validation. Candidates:
- `zio.Runtime$` companion object (the `default` lazy val or `apply` factory)
- `zio.internal.FiberRuntime` first construction (early enough to catch everything)
- The `@Advice.OnMethodExit` fires once at runtime initialization, which is sufficient since `FiberLocalContextHelper.initialize` uses `compareAndSet`

### 4.4 `ZioFiberInstrumentation`

**File**: `instrumentation/zio-2.1/src/main/java/.../ZioFiberInstrumentation.java`

Instruments `FiberRuntime` constructor to propagate context from parent to child fiber. Catches **all** fork paths since every fiber becomes a `FiberRuntime`.

**Reference**: `otel4s-opentelemetry-java/instrumentation/cats-effect-3.6/.../IoFiberInstrumentation.java`

```java
package io.opentelemetry.javaagent.instrumentation.zio.v2_1;

import static net.bytebuddy.matcher.ElementMatchers.*;

import application.io.opentelemetry.context.Context;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import io.opentelemetry.javaagent.instrumentation.zio.common.v2_1.ZioContextSingleton;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class ZioFiberInstrumentation implements TypeInstrumentation {

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("zio.internal.FiberRuntime");
  }

  @Override
  public void transform(TypeTransformer transformer) {
    transformer.applyAdviceToMethod(
        isConstructor(),
        this.getClass().getName() + "$ConstructorAdvice");
  }

  @SuppressWarnings("unused")
  public static final class ConstructorAdvice {
    private ConstructorAdvice() {}

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.This Object self) {
      // Capture current context from parent (via FiberContextBridge → FiberRef)
      Context currentContext = Context.current();
      // Set child fiber's FiberRef to parent's context
      // FiberRuntime extends Fiber.Runtime, which has setFiberRef
      try {
        java.lang.reflect.Method setRef = self.getClass().getMethod(
            "setFiberRef",
            Class.forName("zio.FiberRef"),
            Object.class);
        setRef.invoke(self, ZioContextSingleton.contextFiberRef, currentContext);
      } catch (Exception e) {
        // Fallback: try FiberRefs manipulation
        // If setFiberRef is not accessible, log and continue
        // The fork will still inherit via FiberRefs.forkAs (identity fork = same value)
      }
    }
  }
}
```

**Important considerations**:
- `setFiberRef` is `private[zio]` — the reflection fallback handles this, but we should verify accessibility during testing
- `Context.current()` here is the **application** (unshaded) context via the shaded-for-instrumenting module
- `@Advice.OnMethodExit` is used because we need the constructed `self` reference
- **Alternative if reflection fails**: Instrument `FiberRefs.forkAs` instead, which is called during child fiber creation and has public accessibility

### 4.5 `ZioInstrumentationModule`

**File**: `instrumentation/zio-2.1/src/main/java/.../ZioInstrumentationModule.java`

Registers the instrumentation module with the agent.

**Reference**: `otel4s-opentelemetry-java/instrumentation/cats-effect-3.6/.../CatsEffectInstrumentationModule.java`

```java
package io.opentelemetry.javaagent.instrumentation.zio.v2_1;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;

import com.google.auto.service.AutoService;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.internal.ExperimentalInstrumentationModule;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import java.util.Arrays;
import java.util.List;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumentationModule.class)
public class ZioInstrumentationModule extends InstrumentationModule
    implements ExperimentalInstrumentationModule {

  public ZioInstrumentationModule() {
    super("zio", "zio-2.1");
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    return Arrays.asList(
        new ZioRuntimeInstrumentation(),
        new ZioFiberInstrumentation());
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return hasClassesNamed("zio.ZIO")
        .and(hasClassesNamed("zio.internal.FiberRuntime"));
  }

  @Override
  public boolean defaultEnabled(ConfigProperties config) {
    return super.defaultEnabled(config)
        && config.getBoolean("zio.trackFiberContext", true);
  }

  @Override
  public String getModuleGroup() {
    return "opentelemetry-api-bridge";
  }

  @Override
  public int order() {
    return Integer.MAX_VALUE;  // ensure it runs last
  }
}
```

**File**: `instrumentation/zio-2.1/build.gradle`

```groovy
plugins { id "java" }
apply from: "$rootDir/gradle/instrumentation.gradle"

dependencies {
  implementation project(':opentelemetry-api-shaded-for-instrumenting')
  implementation project(':bootstrap')
  implementation project(':instrumentation:zio-common-2.1')
  compileOnly "dev.zio:zio_${scalaVersion}:${zioVersion}"
}
```

### 4.6 Verify

Run: `./gradlew :instrumentation:zio-2.1:compileJava`

---

## Step 5: zio-telemetry Integration Module

> **Requires**: [Plan A Step 1](plan-zio-telemetry.md#step-1-agent-module--core-classes) — `ZioAgentContext$` class must exist in the published `zio-opentelemetry-agent` artifact.

### 5.1 `ZioAgentContextInstrumentation`

**File**: `instrumentation/zio-telemetry-agent-4.0/src/main/java/.../ZioAgentContextInstrumentation.java`

Instruments `ZioAgentContext$.getAgentFiberRef()` to return the agent's singleton `FiberRef`.

**Reference**: `otel4s-opentelemetry-java/instrumentation/otel4s-0.13/.../IoLocalContextStorageInstrumentation.java`

```java
package io.opentelemetry.javaagent.instrumentation.ziotelemetry.v4_0;

import static net.bytebuddy.matcher.ElementMatchers.*;

import application.io.opentelemetry.context.Context;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import io.opentelemetry.javaagent.instrumentation.zio.common.v2_1.ZioContextSingleton;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class ZioAgentContextInstrumentation implements TypeInstrumentation {

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("zio.telemetry.opentelemetry.agent.ZioAgentContext$");
  }

  @Override
  public void transform(TypeTransformer transformer) {
    transformer.applyAdviceToMethod(
        named("getAgentFiberRef"),
        this.getClass().getName() + "$GetAgentFiberRefAdvice");
  }

  @SuppressWarnings("unused")
  public static final class GetAgentFiberRefAdvice {
    private GetAgentFiberRefAdvice() {}

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.Return(readOnly = false) Object result) {
      // Replace None with Some(ZioContextSingleton.contextFiberRef)
      result = scala.Option.apply(ZioContextSingleton.contextFiberRef);
    }
  }
}
```

### 5.2 `ZioTelemetryInstrumentationModule`

```java
package io.opentelemetry.javaagent.instrumentation.ziotelemetry.v4_0;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;

import com.google.auto.service.AutoService;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.internal.ExperimentalInstrumentationModule;
import java.util.Collections;
import java.util.List;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumentationModule.class)
public class ZioTelemetryInstrumentationModule extends InstrumentationModule
    implements ExperimentalInstrumentationModule {

  public ZioTelemetryInstrumentationModule() {
    super("zio-telemetry-agent", "zio-telemetry-agent-4.0");
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    return Collections.singletonList(new ZioAgentContextInstrumentation());
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return hasClassesNamed("zio.telemetry.opentelemetry.agent.ZioAgentContext$");
  }

  @Override
  public String getModuleGroup() {
    return "opentelemetry-api-bridge";
  }

  @Override
  public int order() {
    return Integer.MAX_VALUE;
  }
}
```

**File**: `instrumentation/zio-telemetry-agent-4.0/build.gradle`

```groovy
plugins { id "java" }
apply from: "$rootDir/gradle/instrumentation.gradle"

dependencies {
  implementation project(':opentelemetry-api-shaded-for-instrumenting')
  implementation project(':instrumentation:zio-common-2.1')
  // Compile-time dependency on zio-telemetry agent module for muzzle checks
  compileOnly "dev.zio:zio-opentelemetry-agent_${scalaVersion}:${zioTelemetryVersion}"
  compileOnly "dev.zio:zio_${scalaVersion}:${zioVersion}"
}
```

### 5.3 Verify

Run: `./gradlew :instrumentation:zio-telemetry-agent-4.0:compileJava`

---

## Step 6: Agent Assembly

### 6.1 `agent/build.gradle`

3-step assembly process (same as otel4s-opentelemetry-java):
1. **Relocate** javaagent libs (shadow JAR with package relocation)
2. **Isolate** relocated libs into `inst/` directory with `.classdata` extension
3. **Merge** with bootstrap libs and upstream agent JAR → `zio-opentelemetry-javaagent.jar`

**Reference**: `otel4s-opentelemetry-java/agent/build.gradle`

```groovy
plugins {
  id "java"
  id "com.gradleup.shadow"
}

dependencies {
  implementation "io.opentelemetry.javaagent:opentelemetry-javaagent:${otelAgentVersion}"
  implementation project(':instrumentation:zio-2.1')
  implementation project(':instrumentation:zio-telemetry-agent-4.0')
  bootstrap project(':bootstrap')
}

// Step 1: Shadow JAR with relocations
shadowJar {
  // Apply standard agent relocations from gradle/shadow.gradle
}

// Step 2: Isolate relocated libs
// Step 3: Merge with upstream agent JAR
```

### 6.2 `testing/agent-for-testing/build.gradle`

A lighter agent JAR used in test JVM arguments.

### 6.3 `custom/build.gradle`

Distribution resource provider (service name, version metadata).

### 6.4 Verify

Run: `./gradlew :agent:shadowJar` — should produce `zio-opentelemetry-javaagent.jar`

---

## Step 7: Unit Tests

### 7.1 Core ZIO Instrumentation Tests

**File**: `instrumentation/zio-2.1/src/test/scala/.../ZioInstrumentationTest.scala`

Tests using raw OTel Java API (`Span`, `tracer.spanBuilder`) with the agent attached.

**Reference**: `otel4s-opentelemetry-java/instrumentation/cats-effect-3.6/src/test/.../CatsEffectInstrumentationTest.scala`

**Test scenarios**:

| # | Test | What It Verifies |
|---|------|-----------------|
| 1 | `respectOuterSpanWithUnsafeRunSync` | Context.current() visible inside ZIO.succeed |
| 2 | `respectOuterSpanAndPropagateToLiftedFuture` | Context propagates through ZIO.fromFuture |
| 3 | `traceIsPropagatedToChildFiber` | `ZIO.fork` inherits parent's span context |
| 4 | `traceIsPropagatedToChildFiberOnExternalExecutor` | Fork on a different ExecutionContext |
| 5 | `traceIsPreservedWhenFiberIsInterrupted` | Interruption doesn't lose span context |
| 6 | `synchronizedFibersDoNotInterfereWithEachOthersTraces` | Sequential forks don't cross-contaminate |
| 7 | `concurrentFibersDoNotInterfereWithEachOthersTraces` | Parallel forks don't cross-contaminate |
| 8 | `sequentialFibersDoNotInterfereWithEachOthersTraces` | Sequential fiber execution maintains isolation |
| 9 | `raceContextIsCorrect` | `ZIO.race` winner's context is preserved |
| 10 | `timeoutContextIsCorrect` | `ZIO.timeout` preserves context |

**Test infrastructure**:
- JVM args: `-javaagent:agent-for-testing.jar -Dzio.trackFiberContext=true -Dotel.javaagent.debug=true`
- Context leak detection: `otel.javaagent.testing.context-leak-detection=true`
- Uses `InMemorySpanExporter` to assert span parent-child relationships

### 7.2 zio-telemetry Integration Tests

**File**: `instrumentation/zio-telemetry-agent-4.0/src/test/scala/.../ZioTelemetryInstrumentationTest.scala`

Tests using zio-telemetry's `Tracer` API with the agent attached.

**Reference**: `otel4s-opentelemetry-java/instrumentation/otel4s-0.13/src/test/.../Otel4sIOLocalContextStorageInstrumentationTest.scala`

**Test scenarios** (all from 7.1, plus):

| # | Test | What It Verifies |
|---|------|-----------------|
| 11 | `agentFiberRefIsDetected` | `ZioAgentContext.isAgentAttached` returns true |
| 12 | `fiberRefContextStorageIsActive` | `OpenTelemetry.agent()` uses `FiberRefContextStorage` |
| 13 | `bidirectionalContextFlow` | Java Context and ZIO FiberRef stay in sync |
| 14 | `autoinstrumentedCompatibility` | `autoinstrumented` still works with new storage |
| 15 | `baggageSurvivesFork` | Baggage propagation across fiber boundaries |
| 16 | `nestedJavaScalaContexts` | Deeply nested Java/ZIO context modifications |

**File**: `instrumentation/zio-telemetry-agent-4.0/build.gradle` (test section)

```groovy
test {
  jvmArgs "-javaagent:${project(':testing:agent-for-testing').shadowJar.archiveFile.get()}"
  jvmArgs "-Dzio.trackFiberContext=true"
  jvmArgs "-Dotel.javaagent.debug=true"
  jvmArgs "-Dotel.javaagent.testing.context-leak-detection=true"
}

dependencies {
  testImplementation "dev.zio:zio_${scalaVersion}:${zioVersion}"
  testImplementation "dev.zio:zio-opentelemetry-agent_${scalaVersion}:${zioTelemetryVersion}"
  testImplementation "io.opentelemetry:opentelemetry-sdk-testing:${otelSdkVersion}"
}
```

### 7.3 Verify

Run: `./gradlew test`

---

## Step 8: Smoke Tests

### 8.1 Smoke Test App

**File**: `smoke-tests-images/zio-http/src/main/scala/example/Server.scala`

A ZIO HTTP server that:
- Creates manual spans via `zio-telemetry`
- Makes outgoing HTTP calls (auto-instrumented by agent)
- Logs with MDC correlation (`trace_id`, `span_id`)

**Reference**: `otel4s-opentelemetry-java/smoke-tests-images/http4s/.../Server.scala`

### 8.2 Smoke Test

**File**: `smoke-tests/src/test/java/.../ZioHttpSmokeTest.java`

Uses Testcontainers:
- Fake OTel backend (OTLP collector in a container)
- ZIO HTTP server with agent attached via `JAVA_TOOL_OPTIONS`
- Verifies: `traceparent` header propagation, span creation, span attributes, distribution resource attributes

**Reference**: `otel4s-opentelemetry-java/smoke-tests/.../Http4sSmokeTest.java`

### 8.3 Version Matrix

**File**: `smoke-tests-images/zio-http/smoke-test-versions.gradle`

```groovy
muzzle {
  pass {
    group = "dev.zio"
    module = "zio-opentelemetry-agent_${scalaVersion}"
    versions = "[4.0.0-RC,)"
  }
  pass {
    group = "dev.zio"
    module = "zio_${scalaVersion}"
    versions = "[2.1.0,)"
  }
}
```

### 8.4 Verify

Run: `./gradlew :smoke-tests:test`

---

## Ordered Implementation Steps

| Order | Step | Description | Depends On |
|-------|------|-------------|-----------|
| 1 | 1.1-1.6 | Build infrastructure (Gradle, settings, shading, instrumentation plugin) | — |
| 2 | 2 | Bootstrap: `FiberLocalContextHelper` | Step 1 |
| 3 | 3 | Common: `ZioContextSingleton` | Step 2 |
| 4 | 4.1-4.2 | `FiberContextBridge` + `FiberContextBridgeInstaller` | Step 3 |
| 5 | 4.3 | `ZioRuntimeInstrumentation` | Step 4 |
| 6 | 4.4 | `ZioFiberInstrumentation` | Step 5 |
| 7 | 4.5 | `ZioInstrumentationModule` (wire up) | Step 6 |
| 8 | 6.1-6.3 | Agent assembly + test agent JAR | Step 7 |
| 9 | 7.1 | Core ZIO instrumentation tests | Step 8 |
| 10 | 5.1-5.3 | zio-telemetry integration module | Plan A Step 1 |
| 11 | 7.2 | zio-telemetry integration tests | Step 10 |
| 12 | 8.1-8.4 | Smoke tests | Step 11, Plan A fully complete |

**Parallel work**: Steps 1-8 can proceed in parallel with Plan A Steps 1-2. Only Step 10 (zio-telemetry integration) is blocked on Plan A.

---

## Challenges & Risk Mitigation

| Challenge | Risk | Mitigation |
|-----------|------|------------|
| `FiberRuntime` constructor signature may change across ZIO versions | High | Use `muzzle-check` plugin. Pin to ZIO 2.1.x initially. |
| `setFiberRef` is `private[zio]` — may not be accessible from agent | Medium | Test reflection accessibility early. Fallback: instrument `FiberRefs.forkAs`. |
| `Runtime.enableCurrentFiber` must be enabled for `_currentFiber` to work | High | Agent auto-enables it via `ZioRuntimeInstrumentation`. Document the requirement. |
| ThreadLocalBridge supervisor adds overhead to every fiber transition | Low | Supervisor only does ThreadLocal reads/writes (nanoseconds). Measure with benchmarks. |
| `ZioContextSingleton` initialization using reflection on ZIO internals | Medium | Test against ZIO 2.1.25. Add muzzle checks for the specific methods used. |
| Agent classloader cannot reference ZIO types | Low (known pattern) | Use only Java types in bootstrap. ZIO types only in instrumentation classloader. |
| Scala 2.12 vs 2.13 vs 3 binary compatibility | Medium | Build against Scala 2.13 ZIO. Verify bytecode compatibility for instrumented methods. |

---

## Key Reference Files (otel4s-opentelemetry-java)

| What | Source Path |
|------|-------------|
| Bootstrap helper | `bootstrap/.../FiberLocalContextHelper.java` |
| Context singleton | `instrumentation/cats-effect-common-3.6/.../IoLocalContextSingleton.java` |
| FiberContextBridge | `instrumentation/cats-effect-3.6/.../FiberContextBridge.java` |
| Bridge installer | `instrumentation/cats-effect-3.6/.../FiberContextBridgeInstaller.java` |
| Runtime instrumentation | `instrumentation/cats-effect-3.6/.../IoRuntimeInstrumentation.java` |
| Fork instrumentation | `instrumentation/cats-effect-3.6/.../IoFiberInstrumentation.java` |
| Module registration | `instrumentation/cats-effect-3.6/.../CatsEffectInstrumentationModule.java` |
| otel4s integration | `instrumentation/otel4s-0.13/.../IoLocalContextStorageInstrumentation.java` |
| otel4s module | `instrumentation/otel4s-0.13/.../Otel4sInstrumentationModule.java` |
| Build config | `build.gradle`, `settings.gradle` |
| Shadow rules | `gradle/shadow.gradle` |
| Instrumentation plugin | `gradle/instrumentation.gradle` |
| Agent assembly | `agent/build.gradle` |
| Unit tests (CE) | `instrumentation/cats-effect-3.6/src/test/.../CatsEffectInstrumentationTest.scala` |
| Unit tests (otel4s) | `instrumentation/otel4s-0.13/src/test/.../Otel4sIOLocalContextStorageInstrumentationTest.scala` |
| Smoke tests | `smoke-tests/.../Http4sSmokeTest.java` |
