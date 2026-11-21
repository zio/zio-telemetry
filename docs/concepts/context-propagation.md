---
id: context-propagation
title: "Context Propagation"
---

A trace is only useful if it spans processes. When service A calls service B, B must learn which trace and span the call belongs to. Context propagation is the mechanism that transfers that information, and ZIO Telemetry models it with carriers and propagators.

## Carriers

The trace information has to travel inside something the protocol already carries, such as HTTP headers or message properties. A carrier is a thin adapter over that container. In ZIO Telemetry there are two kinds. `OutgoingContextCarrier[T]` writes key-value pairs into a container of type `T` when you send a request, and `IncomingContextCarrier[T]` reads them from the container of a request you received.

For the common case there are default implementations backed by a mutable `Map[String, String]`. For any other container, such as the header type of an HTTP library, you implement the small read or write operations of the carrier.

## Propagators

A propagator decides what is written into the carrier and how to read it back. The format is a standard, so that services written in different languages agree on it. ZIO Telemetry has two propagator types:

- `TraceContextPropagator` carries the trace and span IDs. Its `default` instance uses the W3C Trace Context format, which sets the `traceparent` header, and optionally `tracestate`.
- `BaggagePropagator` carries baggage. Its `default` instance uses the W3C Baggage format, which sets the `baggage` header.

Keeping the two separate means that a service can propagate traces without propagating baggage, which can contain data you do not want to send to third parties.

## Injection and Extraction

Injection is the sending side. `Tracing#injectSpan` takes the current span, asks the propagator to write it, and stores the result in an outgoing carrier. The caller then sends the carrier content, for example as request headers.

Extraction is the receiving side. `Tracing#extractSpan` reads an incoming carrier, recovers the remote parent, and starts a child span of it for the duration of the given effect. The baggage equivalents are `Baggage#inject` and `Baggage#extract`.

The result is that both services contribute spans to the same trace, even though they share no memory. See [Context Propagation](../reference/context-propagation.md) for the API and [How to Propagate Context over HTTP](../how-to/propagate-context-over-http.md) for a worked example.
