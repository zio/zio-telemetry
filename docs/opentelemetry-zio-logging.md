---
id: opentelemetry-zio-logging
title: "OpenTelemetry ZIO Logging"
---

`zio-opentelemetry` logging facilities are implemented around OpenTelemetry Logging.

In order to use `zio-opentelemetry` feature with `zio-logging` you should use `zio-opentelemetry-zio-logging` module.

`OpenTelemetry ZIO Logging` contains utilities for combining ZIO Opentelemetry with ZIO Logging

## Installation

```scala
"dev.zio" %% "zio-opentelemetry-zio-logging" % "<version>"
```

## Features

### Log formats

This library implements [Log Format](https://zio.dev/zio-logging/formatting-log-records) for span information (`spanId` and `traceId`)

```scala
ZIO.serviceWithZIO[ContextStorage] { ctxStorage =>
  import zio.logging.console
  import zio.logging.LogFormat._
  import zio.telemetry.opentelemetry.zio.logging.TelemetryLogFormats
  
  val spanIdLabel =  label("spanId", TelemetryLogFormats.spanId(ctxStorage))
  val traceIdLabel = label("traceId", TelemetryLogFormats.traceId(ctxStorage))

  val myLogFormat = timestamp.fixed(32) |-| level |-| label("message", quoted(line)) |-| spanIdLabel |-| traceIdLabel
  val myConsoleLogger = console(myLogFormat)
}
```