---
id: attributes
title: "Attributes"
---

OpenTelemetry attaches key-value pairs, called attributes, to spans and events. The Java API represents them with `AttributeKey[T]` and `Attributes`, which require boxing Scala primitives and building keys by hand. The `zio.telemetry.opentelemetry.common` package provides two small helpers, `Attribute` and `Attributes`, that remove that ceremony. Both helpers produce plain Java values, so everything they build can be passed to any OpenTelemetry API.

## Attribute

`Attribute[T]` pairs a typed Java key with a value of the same type:

```scala
final case class Attribute[T](key: AttributeKey[T], value: T)
```

The companion object offers one constructor per attribute type supported by OpenTelemetry. Each constructor takes the attribute name as a `String` and a Scala value, and boxes the value for you:

| Constructor                                                | Result type                                |
|------------------------------------------------------------|--------------------------------------------|
| `Attribute.string(key: String, value: String)`             | `Attribute[String]`                        |
| `Attribute.boolean(key: String, value: Boolean)`           | `Attribute[java.lang.Boolean]`             |
| `Attribute.long(key: String, value: Long)`                 | `Attribute[java.lang.Long]`                |
| `Attribute.double(key: String, value: Double)`             | `Attribute[java.lang.Double]`              |
| `Attribute.stringList(key: String, values: List[String])`  | `Attribute[java.util.List[String]]`        |
| `Attribute.booleanList(key: String, values: List[Boolean])`| `Attribute[java.util.List[java.lang.Boolean]]` |
| `Attribute.longList(key: String, values: List[Long])`      | `Attribute[java.util.List[java.lang.Long]]`|
| `Attribute.doubleList(key: String, values: List[Double])`  | `Attribute[java.util.List[java.lang.Double]]` |

The list constructors build the array-typed keys (`AttributeKey.stringArrayKey` and friends) and convert the Scala `List` to a `java.util.List`.

We create attributes by calling the constructors, and we can read the underlying key and value from the resulting case class:

```scala mdoc
import zio.telemetry.opentelemetry.common.{Attribute, Attributes}

val userId = Attribute.string("user.id", "u-42")
val retries = Attribute.long("retries", 3L)
val tags = Attribute.stringList("tags", List("a", "b"))

userId.key.getKey
```

Because `Attribute` is an ordinary case class, we can also wrap a key we already have, for example one from a semantic-conventions library:

```scala mdoc:silent
import io.opentelemetry.api.common.AttributeKey

val httpStatus: Attribute[java.lang.Long] =
  Attribute(AttributeKey.longKey("http.response.status_code"), 200L: java.lang.Long)
```

## Attributes

`Attributes` is a companion-style helper object with the same name as the Java class `io.opentelemetry.api.common.Attributes`. Every method returns the Java `Attributes` type, which is what the `attributes` parameters of `Tracing#span`, `Tracing#root`, `Tracing#extractSpan` and the other span-creating methods expect.

| Method                                                 | Description                                                |
|--------------------------------------------------------|------------------------------------------------------------|
| `Attributes.empty`                                     | An empty `Attributes` instance                             |
| `Attributes(a1)` to `Attributes(a1, ..., a6)`          | Builds `Attributes` from one to six `Attribute` values     |
| `Attributes.fromList(attributes: List[Attribute[T]])`  | Builds `Attributes` from a list of attributes of one type  |

The `apply` overloads accept attributes of different value types, so `Attributes(Attribute.string(...), Attribute.long(...))` compiles. `fromList` requires every element to share the same type parameter `T`, so it is only convenient for homogeneous lists. If you need more than six mixed attributes, use the Java builder directly (`io.opentelemetry.api.common.Attributes.builder()`) or set the extra ones afterwards with `Tracing#setAttribute`, described in [Tracing](tracing.md).

We build `Attributes` with the `apply` overloads:

```scala mdoc
val attrs = Attributes(userId, retries)

attrs.size()
```

When all attributes share a type we can use `fromList`:

```scala mdoc
val names = Attributes.fromList(List(Attribute.string("a", "1"), Attribute.string("b", "2")))

names.size()
```

## Using Attributes with Tracing

Both helpers are consumed by `Tracing`. The `attributes` parameter of the span-creating methods takes an `Attributes` value that is attached when the span starts. `Tracing#setAttribute` takes a single `Attribute[T]` and sets it on the current span, and `Tracing#addEventWithAttributes` takes an `Attributes` value for an event.

The following example shows all three usages in one place:

```scala mdoc:compile-only
import zio._
import zio.telemetry.opentelemetry.tracing.Tracing

val program: ZIO[Tracing, Nothing, Unit] =
  ZIO.serviceWithZIO[Tracing] { tracing =>
    tracing.span("checkout", attributes = Attributes(userId, retries)) {
      tracing.setAttribute(Attribute.boolean("cache.hit", true)) *>
        tracing.addEventWithAttributes("payment-authorized", Attributes(Attribute.string("provider", "acme")))
    }
  }
```

For the complete list of `Tracing` methods, see [Tracing](tracing.md).
