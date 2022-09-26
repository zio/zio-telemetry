package zio.telemetry.opentracing

import io.opentracing.mock.{ MockSpan, MockTracer }
import io.opentracing.propagation.{ BinaryAdapters, Format, TextMapAdapter }
import zio._
import zio.test._
import zio.test.Assertion._
import zio.test.ZIOSpecDefault

import scala.collection.mutable
import scala.jdk.CollectionConverters._

import java.nio.ByteBuffer

object OpenTracingTest extends ZIOSpecDefault {

  val mockTracer: Layer[Nothing, MockTracer] =
    ZLayer.fromZIO(ZIO.succeed(new MockTracer))

  val testService: URLayer[MockTracer, OpenTracing] =
    ZLayer.scoped(ZIO.service[MockTracer].flatMap(OpenTracing.scoped(_, "ROOT")))

  val customLayer: ULayer[MockTracer with OpenTracing] = mockTracer ++ (mockTracer >>> testService)

  def spec =
    suite("zio opentracing")(
      test("managedService") {
        val tracer = new MockTracer

        ZIO
          .scoped(
            OpenTracing
              .live(tracer, "ROOT")
              .build
          )
          .as(
            assert(tracer.finishedSpans.asScala)(hasSize(equalTo(1))) && assert(tracer.finishedSpans().get(0))(
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
      },
      suite("spans")(
        test("childSpan") {
          for {
            tracer  <- ZIO.service[MockTracer]
            tracing <- ZIO.service[OpenTracing]
            _       <- tracing.span("ROOT")(tracing.span("Child")(ZIO.unit))
          } yield {
            val spans = tracer.finishedSpans.asScala
            val root  = spans.find(_.operationName() == "ROOT")
            val child = spans.find(_.operationName() == "Child")
            assert(root)(isSome(anything)) &&
            assert(child)(
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
        test("rootSpan") {
          for {
            tracer  <- ZIO.service[MockTracer]
            tracing <- ZIO.service[OpenTracing]
            _       <- tracing.root("ROOT")(tracing.root("ROOT2")(ZIO.unit))
          } yield {
            val spans = tracer.finishedSpans.asScala
            val root  = spans.find(_.operationName() == "ROOT")
            val child = spans.find(_.operationName() == "ROOT2")
            assert(root)(isSome(anything)) &&
            assert(child)(
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
        test("spanFrom behaves like root if extract returns null") {
          val tm = new TextMapAdapter(mutable.Map.empty[String, String].asJava)

          for {
            tracer  <- ZIO.service[MockTracer]
            tracing <- ZIO.service[OpenTracing]
            _       <- tracing.spanFrom(Format.Builtin.TEXT_MAP, tm, "spanFrom")(ZIO.unit)
          } yield {
            val spans    = tracer.finishedSpans.asScala
            val spanFrom = spans.find(_.operationName() == "spanFrom")
            assert(spanFrom)(
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
        test("spanFrom is a no-op if extract throws") {
          val byteBuffer = ByteBuffer.wrap("corrupted binary".toCharArray.map(x => x.toByte))
          val tm         = BinaryAdapters.extractionCarrier(byteBuffer)

          for {
            tracer  <- ZIO.service[MockTracer]
            tracing <- ZIO.service[OpenTracing]
            _       <- tracing.spanFrom(Format.Builtin.BINARY_EXTRACT, tm, "spanFrom")(ZIO.unit)
          } yield {
            val spans    = tracer.finishedSpans.asScala
            val spanFrom = spans.find(_.operationName() == "spanFrom")
            assert(spanFrom)(isNone)
          }
        },
        test("inject - extract roundtrip") {
          val tm = new TextMapAdapter(mutable.Map.empty[String, String].asJava)

          for {
            tracer       <- ZIO.service[MockTracer]
            tracing      <- ZIO.service[OpenTracing]
            injectExtract = tracing.span("foo")(tracing.inject(Format.Builtin.TEXT_MAP, tm)) *>
                              tracing.span("bar")(
                                tracing.spanFrom(Format.Builtin.TEXT_MAP, tm, "baz")(ZIO.unit)
                              )
            _            <- tracing.span("ROOT")(injectExtract)
          } yield {
            val spans = tracer.finishedSpans().asScala
            val root  = spans.find(_.operationName() == "ROOT")
            val foo   = spans.find(_.operationName() == "foo")
            val bar   = spans.find(_.operationName() == "bar")
            val baz   = spans.find(_.operationName() == "baz")
            assert(root)(isSome(anything)) &&
            assert(foo)(isSome(anything)) &&
            assert(bar)(isSome(anything)) &&
            assert(baz)(isSome(anything)) &&
            assert(foo.get.parentId())(equalTo(root.get.context().spanId())) &&
            assert(bar.get.parentId())(equalTo(root.get.context().spanId())) &&
            assert(baz.get.parentId())(equalTo(foo.get.context().spanId()))
          }
        },
        test("tagging") {
          for {
            tracer  <- ZIO.service[MockTracer]
            tracing <- ZIO.service[OpenTracing]

            _ <- tracing.span("foo")(
                   tracing.tag("string", "foo")(
                     tracing.tag("int", 1)(
                       tracing.tag("boolean", true)(ZIO.unit)
                     )
                   )
                 )
          } yield {
            val tags     = tracer.finishedSpans().asScala.head.tags.asScala.toMap
            val expected = Map[String, Any]("boolean" -> true, "int" -> 1, "string" -> "foo")

            assert(tags)(equalTo(expected))
          }
        },
        test("logging") {
          val duration = 1000.micros

          for {
            tracer  <- ZIO.service[MockTracer]
            tracing <- ZIO.service[OpenTracing]
            log      = tracing.log("message")(ZIO.unit) *>
                         tracing.log(Map("msg" -> "message", "size" -> 1))(TestClock.adjust(duration))
            _       <- tracing.span("foo")(log)
          } yield {
            val tags =
              tracer
                .finishedSpans()
                .asScala
                .collect {
                  case span if span.operationName == "foo" =>
                    span.logEntries().asScala.map(le => le.timestampMicros -> le.fields.asScala.toMap)
                }
                .flatten
                .toList

            val expected = List(
              0L    -> Map("event" -> "message"),
              1000L -> Map[String, Any]("msg" -> "message", "size" -> 1)
            )

            assert(tags)(equalTo(expected))
          }
        },
        test("baggage") {
          ZIO.serviceWithZIO[OpenTracing] { tracing =>
            for {
              _      <- tracing.setBaggageItem("foo", "bar")(ZIO.unit)
              _      <- tracing.setBaggageItem("bar", "baz")(ZIO.unit)
              fooBag <- tracing.getBaggageItem("foo")
              barBag <- tracing.getBaggageItem("bar")
            } yield assert(fooBag)(isSome(equalTo("bar"))) &&
              assert(barBag)(isSome(equalTo("baz")))
          }
        }
      ).provideLayer(customLayer)
    )
}
