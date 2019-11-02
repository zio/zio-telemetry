package zio.telemetry

import zio.test.DefaultRunnableSpec
import io.opentracing.mock.MockTracer
import zio.UIO
import zio.test._
import zio.test.Assertion._
import zio.telemetry.TelemetryTestUtils._
import zio.telemetry.Telemetry._
import scala.jdk.CollectionConverters._
import zio.clock.Clock
import zio.ZIO
import zio.ZManaged
import io.opentracing.mock.MockSpan

object TelemetryTest
    extends DefaultRunnableSpec(
      suite("zio opentracing")(
        testM("managedService") {
          makeTracer.flatMap {
            tracer =>
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
            _ <- makeService(tracer).use(UIO.unit.span("Child").provide)
          } yield {
            val spans = tracer.finishedSpans.asScala
            val root = spans.find(_.operationName() == "ROOT")
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
            _ <- makeService(tracer).use(UIO.unit.root("ROOT2").provide)
          } yield {
            val spans = tracer.finishedSpans.asScala
            val root = spans.find(_.operationName() == "ROOT")
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
        }
      )
    )

object TelemetryTestUtils {

  def makeTracer: UIO[MockTracer] = UIO.succeed(new MockTracer)

  def makeService(
      tracer: MockTracer
  ): ZManaged[Clock, Nothing, Clock with Telemetry] =
    for {
      clockService <- ZIO.environment[Clock].toManaged_
      telemetryService <- managed(tracer)
    } yield new Clock with Telemetry {
      override val clock: Clock.Service[Any] = clockService.clock
      override def telemetry: Telemetry.Service = telemetryService
    }

}
