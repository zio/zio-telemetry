---
id: opentelemetry-zio-logging
title: "OpenTelemetry ZIO Logging"
---

`zio-opentelemetry` logging facilities are implemented around OpenTelemetry Logging.

In order to use `zio-opentelemetry` feature with `zio-logging` you should use `zio-opentelemetry-zio-logging` module.

`OpenTelemetry ZIO Logging` contains utilities for combining ZIO Opentelemetry with ZIO Logging

## Installation

```scala
"dev.zio" %% "zio-opentelemetry-zio-logging" % "@VERSION@"
```

## Features

### Log formats

This library implements [Log Format](https://zio.dev/zio-logging/formatting-log-records) for span information (`spanId` and `traceId`). 
To use them you need a `LogFormats` service in the environment. For this, use the `ZioLogging.logFormats` layer, which takes the `core.OpenTelemetry` service as its input. Read the [OpenTelemetry](opentelemetry.md) page first for how to build that service.

The example below prints each log line with the span and trace IDs of the span it runs in. The `OpenTelemetry.installLogger` layer sends the same log records to OpenTelemetry Logs.

```scala mdoc:compile-only
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.exporter.logging.otlp.{OtlpJsonLoggingLogRecordExporter, OtlpJsonLoggingSpanExporter}
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.`export`.SimpleLogRecordProcessor
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import io.opentelemetry.semconv.ServiceAttributes
import zio._
import zio.logging.LogFormat._
import zio.logging.{ConsoleLoggerConfig, LogFilter, consoleLogger}
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.core
import zio.telemetry.opentelemetry.core.trace.Tracer
import zio.telemetry.opentelemetry.zio.logging.{LogFormats, ZioLogging}

object ZioLoggingApp extends ZIOAppDefault {

  val instrumentationScopeName = "dev.zio.ZioLoggingApp"
  val resourceName             = "logging-app"

  val resource: Resource = Resource.create(Attributes.of(ServiceAttributes.SERVICE_NAME, resourceName))

  // Prints to stdout in OTLP Json format
  val stdoutLoggerProvider: RIO[Scope, SdkLoggerProvider] =
    for {
      logRecordExporter  <- ZIO.fromAutoCloseable(ZIO.succeed(OtlpJsonLoggingLogRecordExporter.create()))
      logRecordProcessor <- ZIO.fromAutoCloseable(ZIO.succeed(SimpleLogRecordProcessor.create(logRecordExporter)))
      loggerProvider     <-
        ZIO.fromAutoCloseable(
          ZIO.succeed(
            SdkLoggerProvider
              .builder()
              .setResource(resource)
              .addLogRecordProcessor(logRecordProcessor)
              .build()
          )
        )
    } yield loggerProvider

  // Prints to stdout in OTLP Json format
  val stdoutTracerProvider: RIO[Scope, SdkTracerProvider] =
    for {
      spanExporter   <- ZIO.fromAutoCloseable(ZIO.succeed(OtlpJsonLoggingSpanExporter.create()))
      spanProcessor  <- ZIO.fromAutoCloseable(ZIO.succeed(SimpleSpanProcessor.create(spanExporter)))
      tracerProvider <-
        ZIO.fromAutoCloseable(
          ZIO.succeed(
            SdkTracerProvider
              .builder()
              .setResource(resource)
              .addSpanProcessor(spanProcessor)
              .build()
          )
        )
    } yield tracerProvider

  val otelSdkLayer: TaskLayer[core.OpenTelemetry] =
    OpenTelemetry.custom(
      for {
        tracerProvider <- stdoutTracerProvider
        loggerProvider <- stdoutLoggerProvider
        sdk            <- ZIO.fromAutoCloseable(
                            ZIO.succeed(
                              OpenTelemetrySdk
                                .builder()
                                .setTracerProvider(tracerProvider)
                                .setLoggerProvider(loggerProvider)
                                .build()
                            )
                          )
      } yield sdk
    )

  // Setup zio-logging with spanId and traceId labels
  val loggingLayer: URLayer[LogFormats, Unit] =
    ZLayer.fromZIO(ZIO.service[LogFormats].map { logFormats =>
      val format =
        timestamp.fixed(32) |-|
          level |-|
          label("message", quoted(line)) |-|
          logFormats.spanIdLabel |-|
          logFormats.traceIdLabel
      Runtime.removeDefaultLoggers >>>
        consoleLogger(ConsoleLoggerConfig(format, LogFilter.LogLevelByNameConfig(LogLevel.Info)))
    }).flatten

  override def run =
    ZIO
      .serviceWithZIO[Tracer] { tracer =>
        val logic = for {
          // Read user input
          message <- Console.readLine
          // Print span and trace ids along with message
          _       <- ZIO.logInfo(s"User message: $message")
        } yield ()

        // All log messages produced by `logic` will be correlated with a "root_span" automatically
        logic @@ tracer.aspects.root("root_span", SpanKind.INTERNAL)
      }
      .provide(
        otelSdkLayer,
        OpenTelemetry.installLogger(instrumentationScopeName),
        OpenTelemetry.tracer(instrumentationScopeName),
        ZioLogging.logFormats,
        loggingLayer
      )

}
```
