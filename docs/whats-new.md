---
id: whats-new
title: "What's New"
description: "A summary of what changed in ZIO Telemetry from 3.0.0 through 3.1.21, grouped by theme, taken from the GitHub release notes."
keywords:
  - "Release Notes"
  - "Changelog"
  - "ZIO Telemetry 3"
  - "Upgrading"
---

This page summarises the user-visible changes in the 3.x line of ZIO Telemetry, from 3.0.0 to 3.1.21, which is the latest stable release. The full, authoritative notes for every version are on the [GitHub releases page](https://github.com/zio/zio-telemetry/releases).

Almost all releases after 3.1.0 only update dependencies, so this page groups the changes by theme instead of listing each version. For the exact version where a change landed, follow the version numbers in the sections below.

## Release Lines at a Glance

- **3.0.x (3.0.0 to 3.0.2)**: the release that completed the OpenTelemetry module with all signals, after 26 release candidates.
- **3.1.x (3.1.0 to 3.1.21)**: a small API fix in 3.1.0, followed by regular maintenance releases that track new versions of ZIO, OpenTelemetry, and Scala.
- **3.1.22**: a draft on GitHub at the time of writing, so it is not released yet. Its notes list updates to `zio-logging` 2.5.4, OpenTelemetry Java 1.68.0, and OpenTelemetry semantic conventions 1.45.0.
- **4.0.0-RC releases**: release candidates (4.0.0-RC1 through 4.0.0-RC14) are published from a separate branch. They are not part of this documentation, and this page does not describe their API.

## OpenTelemetry Signals

The 3.0.0 release notes describe the main change since 2.0. Most of the effort went into maturing the `zio-opentelemetry` module. In addition to traces, the module now supports the rest of the OpenTelemetry signals:

- Metrics
- Logs
- Baggage

The public APIs of all modules were polished and documented in the same release. The notes also state that the module is where most of the future development is expected, and that `zio-opencensus` and `zio-opentracing` will gradually fade away. See [Choosing a Module](concepts/choosing-a-module.md) for guidance on which module to use.

## Java Agent Compatibility

3.0.0 also implements the correct synchronization between the ZIO 2 runtime and the OpenTelemetry Java auto-instrumentation agent. The release notes link to the upstream change in the `opentelemetry-java-instrumentation` repository that made this possible. See [How to Use the Java Agent](how-to/use-the-java-agent.md) to use this integration.

## API Fixes

3.1.0 fixes the method resolution of the `Attributes.apply` methods. The release notes call out that the change is binary incompatible with 3.0.x. The maintainers decided not to bump the major version because the change is small and most users would not notice it. If you publish a library that depends on `zio-opentelemetry`, make sure that you and your users compile against the same 3.x version.

3.1.4 replaces the deprecated `ResourceAttributes` with `ServiceAttributes` from the OpenTelemetry semantic conventions.

## Dependency Updates

The 3.x line tracks the upstream libraries closely. The versions below are the ones named in the release notes. They show how far the line has moved, not a compatibility matrix.

- **OpenTelemetry Java** (`opentelemetry-api` and related artifacts): 1.43.0 in 3.0.0, and 1.67.0 in 3.1.21.
- **OpenTelemetry semantic conventions** (`opentelemetry-semconv`): 1.30.0 in 3.1.2, and 1.44.0 in 3.1.20.
- **ZIO**: 2.1.9 and 2.1.11 in the 3.0.0 notes, and 2.1.27 in 3.1.21.
- **ZIO Logging**: 2.3.2 in 3.0.0, 2.4.0 in 3.0.2, 2.5.0 in 3.1.2, and 2.5.2 in 3.1.13.
- **Scala 3**: 3.5.1 in 3.0.0, and 3.9.0 in 3.1.21.

## Build and Documentation Maintenance

Several 3.1.x releases changed only the project's own tooling:

- 3.1.1 fixes an "sbt not found" error in CI.
- 3.1.8 configures Scala Steward and Dependabot in place of Renovate.
- 3.1.11 removes the `sbt-bloop` plugin.
- 3.1.20 updates `zio-sbt`, ignores generated website documentation, and uses the `@VERSION@` placeholder in the installation snippets of the docs.
- The 3.1.21 notes include updates to the GitHub Actions used by the project's CI.

These changes do not affect the published artifacts' API.
