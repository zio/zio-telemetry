package zio.telemetry

import io.opentracing.mock.MockSpan
import io.opentracing.mock.MockTracer
import io.opentracing.propagation.Format
import io.opentracing.propagation.TextMapAdapter
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import zio.clock.Clock
import zio.telemetry.Telemetry._
import zio.telemetry.TelemetryTestUtils._
import zio.test._
import zio.test.Assertion._
import zio.test.DefaultRunnableSpec
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
            tm = new TextMapAdapter(mutable.Map.empty.asJava)
            _ <- makeService(tracer).use((for {
              _ <- inject(Format.Builtin.TEXT_MAP, tm).span("foo")
              _ <- UIO.unit
                .spanFrom(Format.Builtin.TEXT_MAP, tm, "baz")
                .span("bar")
            } yield ()).provide)
          } yield {
            val spans = tracer.finishedSpans().asScala
            val root = spans.find(_.operationName() == "ROOT")
            val foo = spans.find(_.operationName() == "foo")
            val bar = spans.find(_.operationName() == "bar")
            val baz = spans.find(_.operationName() == "baz")
            assert(root, isSome(anything)) &&
            assert(foo, isSome(anything)) &&
            assert(bar, isSome(anything)) &&
            assert(baz, isSome(anything)) &&
            assert(foo.get.parentId(), equalTo(root.get.context().spanId())) &&
            assert(bar.get.parentId(), equalTo(root.get.context().spanId())) &&
            assert(baz.get.parentId(), equalTo(foo.get.context().spanId()))
          }
        }
      )
    )

object TelemetryTestUtils {

  def makeTracer: UIO[MockTracer] = UIO.succeed(new MockTracer)

  def makeService(
    tracer: MockTracer
  ): ZManaged[Clock, Nothing, Clock with Telemetry] =
    for {
      clockService     <- ZIO.environment[Clock].toManaged_
      telemetryService <- managed(tracer)
    } yield new Clock with Telemetry {
      override val clock: Clock.Service[Any]    = clockService.clock
      override def telemetry: Telemetry.Service = telemetryService
    }

}
