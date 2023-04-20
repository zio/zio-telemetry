---
id: index
title: "Introduction to ZIO Telemetry"
sidebar_label: "ZIO Telemetry"
---

[ZIO telemetry](https://github.com/zio/zio-telemetry) is purely-functional and type-safe. It provides clients for
[OpenTracing](https://opentracing.io/), [OpenCensus](https://opencensus.io/) and [OpenTelemetry](https://opentelemetry.io/).

@PROJECT_BADGES@

ZIO Telemetry consists of the following projects:

- [OpenTracing](opentracing.md)
- [OpenCensus](opencensus.md)
- [OpenTelemetry](opentelemetry.md)

## Introduction

In monolithic architecture, everything is in one place, and we know when a request starts and then how it goes through 
the components and when it finishes. We can obviously see what is happening with our request and where is it going. 
But, in distributed systems like microservice architecture, we cannot find out the story of a request through various 
services easily. This is where distributed tracing comes into play.

ZIO Telemetry is a purely functional client which helps up propagate context between services in a distributed environment.

## Installation

In order to use this library, we need to add the following line in our `build.sbt` file if we want to use [OpenTelemetry](https://opentelemetry.io/) client:

```scala
libraryDependencies += "dev.zio" %% "zio-opentelemetry" % "<version>"
```

For using [OpenTracing](https://opentracing.io/) client we should add the following line in our `build.sbt` file:

```scala
libraryDependencies += "dev.zio" %% "zio-opentracing" % "<version>"
```

And for using [OpenCensus](https://opencensus.io/) client we should add the following line in our `build.sbt` file:

```scala
libraryDependencies += "dev.zio" %% "zio-opencensus" % "<version>"
```

## Articles

- [Trace your microservices with ZIO](https://kadek-marek.medium.com/trace-your-microservices-with-zio-telemetry-5f88d69cb26b) by Marek Kadek (September 2021)
