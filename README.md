[//]: # (This file was autogenerated using `zio-sbt-website` plugin via `sbt generateReadme` command.)
[//]: # (So please do not edit it manually. Instead, change "docs/index.md" file or sbt setting keys)
[//]: # (e.g. "readmeDocumentation" and "readmeSupport".)

# ZIO Telemetry

[ZIO telemetry](https://github.com/zio/zio-telemetry) is purely-functional and type-safe. It provides clients for
[OpenTracing](https://opentracing.io/), [OpenCensus](https://opencensus.io/) and [OpenTelemetry](https://opentelemetry.io/).

[![Production Ready](https://img.shields.io/badge/Project%20Stage-Production%20Ready-brightgreen.svg)](https://github.com/zio/zio/wiki/Project-Stages) ![CI Badge](https://github.com/zio/zio-telemetry/workflows/CI/badge.svg) [![Sonatype Releases](https://img.shields.io/nexus/r/https/oss.sonatype.org/dev.zio/zio-opentracing_2.12.svg?label=Sonatype%20Release)](https://oss.sonatype.org/content/repositories/releases/dev/zio/zio-opentracing_2.12/) [![Sonatype Snapshots](https://img.shields.io/nexus/s/https/oss.sonatype.org/dev.zio/zio-opentracing_2.12.svg?label=Sonatype%20Snapshot)](https://oss.sonatype.org/content/repositories/snapshots/dev/zio/zio-opentracing_2.12/) [![javadoc](https://javadoc.io/badge2/dev.zio/zio-telemetry-docs_2.12/javadoc.svg)](https://javadoc.io/doc/dev.zio/zio-telemetry-docs_2.12) [![ZIO Telemetry](https://img.shields.io/github/stars/zio/zio-telemetry?style=social)](https://github.com/zio/zio-telemetry)

ZIO Telemetry consists of the following projects:

- [OpenTracing](docs/opentracing.md)
- [OpenCensus](docs/opencensus.md)
- [OpenTelemetry](docs/opentelemetry.md)

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

## Examples

You can find examples with full source code and instructions of how to run by following the links:
- [OpenTelemetry Example](docs/https://github.com/zio/zio-telemetry/blob/series/2.x/docs/opentelemetry-example.md)
- [OpenTracing Example](docs/https://github.com/zio/zio-telemetry/blob/series/2.x/docs/opentracing-example.md)

## Articles

- [Trace your microservices with ZIO](https://kadek-marek.medium.com/trace-your-microservices-with-zio-telemetry-5f88d69cb26b) by Marek Kadek (September 2021)

## Documentation

Learn more on the [ZIO Telemetry homepage](https://zio.dev/zio-telemetry/)!

## Contributing

For the general guidelines, see ZIO [contributor's guide](https://zio.dev/about/contributing).

## Code of Conduct

See the [Code of Conduct](https://zio.dev/about/code-of-conduct)

## Support

Come chat with us on [![Badge-Discord]][Link-Discord].

[Badge-Discord]: https://img.shields.io/discord/629491597070827530?logo=discord "chat on discord"
[Link-Discord]: https://discord.gg/2ccFBr4 "Discord"

## License

[License](LICENSE)
