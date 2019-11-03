package zio.telemetry

import io.opentracing.mock.MockSpan
import io.opentracing.mock.MockTracer
import io.opentracing.propagation.Format
import io.opentracing.propagation.TextMapAdapter
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import zio.duration._
import zio.telemetry.opentracing._
import zio.telemetry.TelemetryTestUtils._
import zio.test._
import zio.test.Assertion._
import zio.test.DefaultRunnableSpec
import zio.test.environment.TestClock
import zio.UIO
import zio.ZIO
import zio.ZManaged

object TelemetryTest
    extends DefaultRunnableSpec(
      suite("zio opentracing")(
        testM("managedService") {
          makeTracer.flatMap { tracer =>
            managed(tracer)
              .use_(UIO.unit)
              .map(
                _ =>
                  assert(tracer.finishedSpans.asScala, hasSize(equalTo(1))) && assert(
                    tracer.finishedSpans().get(0),
                    hasField[MockSpan, String](
                      "operationName",
                      _.operationName(),
                      equalTo("ROOT")
                    ) &&
                      hasField[MockSpan, Long](
                        "parent",
                        _.parentId,
                        equalTo(0L)
                      )
                  )
              )
          }
        },
        testM("childSpan") {
          for {
            tracer <- makeTracer
            _      <- makeService(tracer).use(UIO.unit.span("Child").provide)
          } yield {
            val spans = tracer.finishedSpans.asScala
            val root  = spans.find(_.operationName() == "ROOT")
            val child = spans.find(_.operationName() == "Child")
            assert(root, isSome(anything)) &&
            assert(
              child,
              isSome(
                hasField[MockSpan, Long](
                  "parent",
                  _.parentId,
                  equalTo(root.get.context().spanId())
                )
              )
            )
          }
        },
        testM("rootSpan") {
          for {
            tracer <- makeTracer
            _      <- makeService(tracer).use(UIO.unit.root("ROOT2").provide)
          } yield {
            val spans = tracer.finishedSpans.asScala
            val root  = spans.find(_.operationName() == "ROOT")
            val child = spans.find(_.operationName() == "ROOT2")
            assert(root, isSome(anything)) &&
            assert(
              child,
              isSome(
                hasField[MockSpan, Long](
                  "parent",
                  _.parentId,
                  equalTo(0L)
                )
              )
            )
          }
        },
        testM("inject - extract roundtrip") {
          for {
            tracer <- makeTracer
            tm     = new TextMapAdapter(mutable.Map.empty.asJava)
            _ <- makeService(tracer).use { env =>
                  (env.telemetry.inject(Format.Builtin.TEXT_MAP, tm).span("foo") *>
                    env.telemetry
                      .spanFrom(Format.Builtin.TEXT_MAP, tm, UIO.unit, "baz")
                      .span("bar"))
                    .provide(env)
                }
          } yield {
            val spans = tracer.finishedSpans().asScala
            val root  = spans.find(_.operationName() == "ROOT")
            val foo   = spans.find(_.operationName() == "foo")
            val bar   = spans.find(_.operationName() == "bar")
            val baz   = spans.find(_.operationName() == "baz")
            assert(root, isSome(anything)) &&
            assert(foo, isSome(anything)) &&
            assert(bar, isSome(anything)) &&
            assert(baz, isSome(anything)) &&
            assert(foo.get.parentId(), equalTo(root.get.context().spanId())) &&
            assert(bar.get.parentId(), equalTo(root.get.context().spanId())) &&
            assert(baz.get.parentId(), equalTo(foo.get.context().spanId()))
          }
        },
        testM("tagging") {
          for {
            tracer <- makeTracer
            _ <- makeService(tracer).use(
                  env =>
                    (for {
                      _ <- env.telemetry.tag("boolean", true)
                      _ <- env.telemetry.tag("int", 1)
                      _ <- env.telemetry.tag("string", "foo")
                    } yield ()).provide(env)
                )
          } yield {
            val tags     = tracer.finishedSpans().asScala.head.tags.asScala.toMap
            val expected = Map[String, Any]("boolean" -> true, "int" -> 1, "string" -> "foo")
            assert(tags, equalTo(expected))
          }
        },
        testM("logging") {
          for {
            tracer <- makeTracer
            _ <- makeService(tracer).use(
                  env =>
                    (for {
                      _ <- env.telemetry.log("message")
                      _ <- TestClock.adjust(1000.micros)
                      _ <- env.telemetry.log(Map("msg" -> "message", "size" -> 1))
                    } yield ()).provide(env)
                )
          } yield {
            val tags =
              tracer.finishedSpans().asScala.head.logEntries.asScala.map(le => le.timestampMicros -> le.fields.asScala)
            val expected = List(
              0L    -> Map("event"            -> "message"),
              1000L -> Map[String, Any]("msg" -> "message", "size" -> 1)
            )
            assert(tags, equalTo(expected))
          }
        },
        testM("baggage") {
          val test =
            for {
              env    <- ZIO.environment[OpenTracing]
              _      <- env.telemetry.setBaggageItem("foo", "bar")
              _      <- env.telemetry.setBaggageItem("bar", "baz")
              fooBag <- env.telemetry.getBaggageItem("foo")
              barBag <- env.telemetry.getBaggageItem("bar")
            } yield assert(fooBag, isSome(equalTo("bar"))) &&
              assert(barBag, isSome(equalTo("baz")))
          test.provideSomeManaged(makeTracer.toManaged_.flatMap(makeService))
        }
      )
    )

object TelemetryTestUtils {

  def makeTracer: UIO[MockTracer] = UIO.succeed(new MockTracer)

  def makeService(
    tracer: MockTracer
  ): ZManaged[TestClock, Nothing, TestClock with OpenTracing] =
    for {
      clockService <- ZIO.environment[TestClock].toManaged_
      telemetry_   <- managed(tracer)
    } yield new TestClock with OpenTracing {
      override val clock: TestClock.Service[Any]     = clockService.clock
      override val scheduler: TestClock.Service[Any] = clockService.scheduler
      override def telemetry: OpenTracing.Service    = telemetry_.telemetry
    }

}
