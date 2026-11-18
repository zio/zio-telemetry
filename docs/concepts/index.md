---
id: index
title: "Concepts"
---

These pages explain the ideas behind ZIO Telemetry. They do not give step-by-step instructions. Read them to build a mental model, then use the reference and how-to pages to get things done.

The pages cover the following topics:

- [Signals and Spans](signals-and-spans.md) describes the kinds of telemetry data, what a span is, and how a ZIO effect maps onto a span.
- [Context and Fibers](context-and-fibers.md) explains how the current span travels with a fiber and when to use the fiber-based or the thread-local context storage.
- [Context Propagation](context-propagation.md) explains how trace information crosses process boundaries through carriers and propagators.
- [Choosing a Module](choosing-a-module.md) compares the OpenTelemetry, OpenTracing and OpenCensus modules.

If you have not yet run any code, start with [Getting Started](../getting-started.md).
