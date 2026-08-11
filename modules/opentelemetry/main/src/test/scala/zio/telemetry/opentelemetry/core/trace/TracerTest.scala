package zio.telemetry.opentelemetry.core.trace

import io.opentelemetry.api.trace.{Span => JSpan, SpanId, StatusCode}
import io.opentelemetry.context.Context
import zio._
import zio.telemetry.opentelemetry.core.common.{Attribute, Attributes}
import zio.telemetry.opentelemetry.testkit
import zio.telemetry.opentelemetry.testkit.OpenTelemetryTestkit
import zio.telemetry.opentelemetry.testkit.trace.{SpanData, TracerTestkit}
import zio.test.Assertion._
import zio.test.{Assertion, Spec, TestClock, ZIOSpecDefault, assert}

import scala.concurrent.Future

object TracerTest extends ZIOSpecDefault {

  val instrumentationScopeName = "TracerTest"

  def assertSpanStatusCode(assertion: Assertion[StatusCode]): Assertion[SpanData] =
    hasField[SpanData, StatusCode]("statusCode", _.status.statusCode, assertion)

  def assertSpanDescription(assertion: Assertion[String]): Assertion[SpanData] =
    hasField[SpanData, String]("statusDescription", _.status.description, assertion)

  def assertSpanException(assertion: Assertion[List[(String, String)]]): Assertion[SpanData] =
    hasField[SpanData, List[(String, String)]](
      "exceptionAttributes",
      _.events.flatMap(_.attributes.asMap.toList),
      assertion
    )

  def assertSpanParentId(assertion: Assertion[String]): Assertion[SpanData] =
    hasField[SpanData, String](
      "parentSpanId",
      _.parentSpanId,
      assertion
    )

  def spec: Spec[Any, Throwable] =
    suite("zio opentelemetry")(
      suite("Tracer")(
        spansSpec,
        spanScopedSpec,
        spanOperationsSpec,
        statusMapperSpec,
        spanWithLogAnnotationsSpec
      )
    )

  private val spansSpec =
    suite("spans")(
      test("root") {
        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          tracer        <- tracerTestkit.getTracer(instrumentationScopeName)
          assertParentId = assertSpanParentId(equalTo(SpanId.getInvalid))
          _             <- ZIO.unit @@ tracer.aspects.root("ROOT2") @@ tracer.aspects.root("ROOT")
          spans         <- tracerTestkit.getFinishedSpans
          root           = spans.find(_.name == "ROOT")
          child          = spans.find(_.name == "ROOT2")
        } yield assert(root)(isSome(anything)) && assert(child)(isSome(assertParentId))
      },
      test("span") {
        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          tracer        <- tracerTestkit.getTracer(instrumentationScopeName)
          _             <- ZIO.unit @@ tracer.aspects.span("Child") @@ tracer.aspects.span("Root")
          spans         <- tracerTestkit.getFinishedSpans
          root           = spans.find(_.name == "Root")
          child          = spans.find(_.name == "Child")
        } yield assert(root)(isSome(anything)) &&
          assert(child)(isSome(assertSpanParentId(equalTo(root.get.spanId))))
      },
      test("continueSpan") {
        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          jtracer       <- tracerTestkit.unsafe.getTracer(instrumentationScopeName)
          tracer        <- tracerTestkit.unsafe.getTracerFromJava(jtracer)
          span           = Span.make(jtracer.spanBuilder("external").startSpan())
          scope          = span.unsafe.asJava.makeCurrent()
          _             <- ZIO.unit @@ tracer.aspects.continueSpan(span, "zio-otel-child")
          _             <- span.end
          _              = scope.close()
          spans         <- tracerTestkit.getFinishedSpans
          child          = spans.find(_.name == "zio-otel-child")
        } yield assert(child)(isSome(assertSpanParentId(equalTo(span.context.getSpanId))))
      },
      test("unmanagedScope") {
        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          tracer        <- tracerTestkit.getTracer(instrumentationScopeName)
          _             <- tracer.unmanagedScope {
                             val span = JSpan.current()
                             span.addEvent("In legacy code")
                             if (Context.current() == Context.root()) throw new RuntimeException("Current context is root!")
                             span.addEvent("Finishing legacy code")
                           }.unit @@ tracer.aspects.span("Scoped") @@ tracer.aspects.span("Root")
          spans         <- tracerTestkit.getFinishedSpans
          root           = spans.find(_.name == "Root")
          scoped         = spans.find(_.name == "Scoped")
          eventNames     = scoped.get.events.map(_.name)
        } yield assert(root)(isSome(anything)) &&
          assert(scoped)(isSome(assertSpanParentId(equalTo(root.get.spanId)))) &&
          assert(eventNames)(equalTo(List("In legacy code", "Finishing legacy code")))
      },
      test("unmanagedScopeTotal") {
        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          tracer        <- tracerTestkit.getTracer(instrumentationScopeName)
          _             <- tracer.unmanagedScopeTotal {
                             val span = JSpan.current()
                             span.addEvent("In legacy code")
                             if (Context.current() == Context.root()) throw new RuntimeException("Current context is root!")
                             Thread.sleep(10)
                             if (Context.current() == Context.root()) throw new RuntimeException("Current context is root!")
                             span.addEvent("Finishing legacy code")
                           }.unit @@ tracer.aspects.span("Scoped") @@ tracer.aspects.span("Root")
          spans         <- tracerTestkit.getFinishedSpans
          root           = spans.find(_.name == "Root")
          scoped         = spans.find(_.name == "Scoped")
          eventNames     = scoped.get.events.map(_.name)
        } yield assert(root)(isSome(anything)) &&
          assert(scoped)(isSome(assertSpanParentId(equalTo(root.get.spanId)))) &&
          assert(eventNames)(equalTo(List("In legacy code", "Finishing legacy code")))
      },
      test("unmanagedScopeFuture") {
        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          tracer        <- tracerTestkit.getTracer(instrumentationScopeName)
          result        <- tracer.unmanagedScopeFuture { _ =>
                             Future.successful {
                               val span = JSpan.current()
                               span.addEvent("In legacy code")
                               if (Context.current() == Context.root())
                                 throw new RuntimeException("Current context is root!")
                               span.addEvent("Finishing legacy code")
                               1
                             }
                           } @@ tracer.aspects.span("Scoped") @@ tracer.aspects.span("Root")
          spans         <- tracerTestkit.getFinishedSpans
          root           = spans.find(_.name == "Root")
          scoped         = spans.find(_.name == "Scoped")
          eventNames     = scoped.get.events.map(_.name)
        } yield assert(result)(equalTo(1)) &&
          assert(root)(isSome(anything)) &&
          assert(scoped)(isSome(assertSpanParentId(equalTo(root.get.spanId)))) &&
          assert(eventNames)(equalTo(List("In legacy code", "Finishing legacy code")))
      }
    ).provide(TracerTestkit.inMemory, OpenTelemetryTestkit.ctxStorageZioFiberRef)

  private val spanScopedSpec =
    suite("scoped spans")(
      test("span") {
        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          tracer        <- tracerTestkit.getTracer(instrumentationScopeName)
          _             <- ZIO.scoped[Any](
                             tracer.spanScoped("Root") *> ZIO.scoped[Any](tracer.spanScoped("Child"))
                           )
          spans         <- tracerTestkit.getFinishedSpans
          root           = spans.find(_.name == "Root")
          child          = spans.find(_.name == "Child")
        } yield assert(root)(isSome(anything)) &&
          assert(child)(isSome(assertSpanParentId(equalTo(root.get.spanId))))
      },
      test("span single scope") {
        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          tracer        <- tracerTestkit.getTracer(instrumentationScopeName)
          _             <- ZIO.scoped[Any](
                             for {
                               _ <- tracer.spanScoped("Root")
                               _ <- tracer.spanScoped("Child")
                             } yield ()
                           )
          spans         <- tracerTestkit.getFinishedSpans
          root           = spans.find(_.name == "Root")
          child          = spans.find(_.name == "Child")
        } yield assert(root)(isSome(anything)) &&
          assert(child)(isSome(assertSpanParentId(equalTo(root.get.spanId))))
      },
      test("status mapper for failed span") {
        val assertStatusCode  = assertSpanStatusCode(equalTo(StatusCode.ERROR))
        val assertDescription = assertSpanDescription(equalTo(""))
        val assertException   = assertSpanException(
          hasSubset(List("exception.message" -> "some_error", "exception.type" -> "java.lang.RuntimeException"))
        )

        val assertError  =
          assertStatusCode && assertException && assertDescription
        val statusMapper =
          StatusMapper.failureThrowable(_ => StatusCode.ERROR)

        val failedEffect: ZIO[Any, Throwable, Unit] =
          ZIO.fail(new RuntimeException("some_error")).unit

        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          tracer        <- tracerTestkit.getTracer(instrumentationScopeName)
          _             <- ZIO
                             .scoped[Any](
                               tracer.spanScoped("Root", statusMapper = statusMapper) *>
                                 ZIO.scoped[Any](
                                   tracer.spanScoped("Child", statusMapper = statusMapper) *> failedEffect
                                 )
                             )
                             .ignore
          spans         <- tracerTestkit.getFinishedSpans
          root           = spans.find(_.name == "Root")
          child          = spans.find(_.name == "Child")
        } yield assert(root)(isSome(assertError)) && assert(child)(isSome(assertError))
      },
      test("setAttribute") {
        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          tracer        <- tracerTestkit.getTracer(instrumentationScopeName)
          _             <- ZIO.scoped[Any](for {
                             span <- tracer.spanScoped("foo")
                             _    <- span.setAttribute("string", "bar")
                           } yield ())
          spans         <- tracerTestkit.getFinishedSpans
          tags           = spans.head.attributes
        } yield assert(tags.get[String]("string"))(isSome(equalTo("bar")))
      }
    ).provide(TracerTestkit.inMemory, OpenTelemetryTestkit.ctxStorageZioFiberRef)

  private val spanOperationsSpec =
    suite("span operations")(
      test("setAttribute") {
        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          tracer        <- tracerTestkit.getTracer(instrumentationScopeName)
          _             <- tracer.span("foo") { span =>
                             for {
                               _ <- span.setAttribute("boolean", value = true)
                               _ <- span.setAttribute("int", 1)
                               _ <- span.setAttribute("string", "foo")
                               _ <- span.setAttribute("booleans", Seq(true, false))
                               _ <- span.setAttribute("longs", Seq(1L, 2L))
                               _ <- span.setAttribute("strings", Seq("foo", "bar"))
                             } yield ()
                           }
          spans         <- tracerTestkit.getFinishedSpans
          tags           = spans.head.attributes
        } yield assert(tags.get[Boolean]("boolean"))(isSome(equalTo(true))) &&
          assert(tags.get[Long]("int"))(isSome(equalTo(1L))) &&
          assert(tags.get[String]("string"))(isSome(equalTo("foo"))) &&
          assert(tags.get[List[Boolean]]("booleans"))(
            isSome(equalTo(List(true, false)))
          ) &&
          assert(tags.get[List[Long]]("longs"))(
            isSome(equalTo(List(1L, 2L)))
          ) &&
          assert(tags.get[List[String]]("strings"))(isSome(equalTo(List("foo", "bar"))))
      },
      test("addEvent & addEventWithAttributes") {
        val duration = 1000.micros

        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          tracer        <- tracerTestkit.getTracer(instrumentationScopeName)
          _             <- tracer.span("foo") { span =>
                             for {
                               _ <- span.addEvent("message")
                               _ <- TestClock.adjust(duration)
                               _ <- span.addEventWithAttributes(
                                      "message2",
                                      Attributes(Attribute.string("msg", "message"), Attribute.long("size", 1L))
                                    )
                             } yield ()
                           }
          _             <- ZIO.unit @@ tracer.aspects.span("Child") @@ tracer.aspects.span("Root")
          spans         <- tracerTestkit.getFinishedSpans
          tags           = spans.collect {
                             case span if span.name == "foo" =>
                               span.events
                           }.flatten
        } yield {
          val expected = List(
            SpanData.EventData(
              "message",
              testkit.common.Attributes(Attributes.empty),
              0L
            ),
            SpanData.EventData(
              "message2",
              testkit.common.Attributes(
                Attributes(Attribute.string("msg", "message"), Attribute.long("size", 1L))
              ),
              1000000L
            )
          )
          assert(tags)(equalTo(expected))
        }
      },
      test("addLinks") {
        for {
          tracerTestkit              <- ZIO.service[TracerTestkit]
          jtracer                    <- tracerTestkit.unsafe.getTracer(instrumentationScopeName)
          tracer                     <- tracerTestkit.unsafe.getTracerFromJava(jtracer)
          externallyProvidedRootSpan1 = jtracer.spanBuilder("external1").startSpan()
          externallyProvidedRootSpan2 = jtracer.spanBuilder("external2").startSpan()
          externallyProvidedRootSpan3 = jtracer.spanBuilder("external3").startSpan()
          links                       = List(
                                          externallyProvidedRootSpan1,
                                          externallyProvidedRootSpan2,
                                          externallyProvidedRootSpan3
                                        ).map(_.getSpanContext)
          _                          <- ZIO.unit @@ tracer.aspects.span("Child", links = links) @@ tracer.aspects.span("Root")
          spans                      <- tracerTestkit.getFinishedSpans
          root                        = spans.find(_.name == "Root")
          child                       = spans.find(_.name == "Child")
        } yield assert(root)(isSome(anything)) &&
          assert(child)(isSome(assertSpanParentId(equalTo(root.get.spanId)))) &&
          assert(child.toList.flatMap(_.links.map(_.spanContext.spanId)))(
            hasSameElements(links.map(_.getSpanId))
          )
      }
    ).provide(TracerTestkit.inMemory, OpenTelemetryTestkit.ctxStorageZioFiberRef)

  private val statusMapperSpec =
    suite("status mapper")(
      test("empty") {
        val assertEmptyOkStatusCode  = assertSpanStatusCode(equalTo(StatusCode.UNSET))
        val assertEmptyOkDescription = assertSpanDescription(equalTo(""))

        val assertEmptyFailedStatusCode  = assertSpanStatusCode(equalTo(StatusCode.UNSET))
        val assertEmptyFailedDescription = assertSpanDescription(equalTo(""))

        val assertManuallySetOkStatusCode  = assertSpanStatusCode(equalTo(StatusCode.OK))
        val assertManuallySetOkDescription = assertSpanDescription(equalTo(""))

        val assertManuallySetErrorStatusCode  = assertSpanStatusCode(equalTo(StatusCode.ERROR))
        val assertManuallySetErrorDescription = assertSpanDescription(equalTo("Error"))

        val assertEmptyOk          =
          assertEmptyOkStatusCode && assertEmptyOkDescription
        val assertEmptyFailed      =
          assertEmptyFailedStatusCode && assertEmptyFailedDescription
        val assertManuallySetOk    =
          assertManuallySetOkStatusCode && assertManuallySetOkDescription
        val assertManuallySetError =
          assertManuallySetErrorStatusCode && assertManuallySetErrorDescription

        for {
          tracerTestkit   <- ZIO.service[TracerTestkit]
          tracer          <- tracerTestkit.getTracer(instrumentationScopeName)
          _               <- ZIO.unit @@ tracer.aspects.span("empty-ok", statusMapper = StatusMapper.empty)
          _               <- (
                               ZIO.fail(new RuntimeException("Error"))
                                 @@ tracer.aspects.span("empty-failed", statusMapper = StatusMapper.empty)
                             ).either
          _               <- tracer.span("manually-set-ok", statusMapper = StatusMapper.empty) { span =>
                               span.setStatus(StatusCode.OK, "OK")
                             }
          _               <- tracer.span("manually-set-error", statusMapper = StatusMapper.empty) { span =>
                               span.setStatus(StatusCode.ERROR, "Error")
                             }
          spans           <- tracerTestkit.getFinishedSpans
          emptyOk          = spans.find(_.name == "empty-ok")
          emptyFailed      = spans.find(_.name == "empty-failed")
          manuallySetOk    = spans.find(_.name == "manually-set-ok")
          manuallySetError = spans.find(_.name == "manually-set-error")
        } yield assert(emptyOk)(isSome(assertEmptyOk)) &&
          assert(emptyFailed)(isSome(assertEmptyFailed)) &&
          assert(manuallySetOk)(isSome(assertManuallySetOk)) &&
          assert(manuallySetError)(isSome(assertManuallySetError))
      },
      test("default") {
        val assertOkStatusCode  = assertSpanStatusCode(equalTo(StatusCode.UNSET))
        val assertOkDescription = assertSpanDescription(equalTo(""))

        val assertFailedStatusCode  = assertSpanStatusCode(equalTo(StatusCode.ERROR))
        val assertFailedDescription = assertSpanDescription(equalTo(""))

        val assertDefaultOk     =
          assertOkStatusCode && assertOkDescription
        val assertDefaultFailed =
          assertFailedStatusCode && assertFailedDescription

        val assertErrorExceptionEmpty = assertSpanException(isEmpty)

        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          tracer        <- tracerTestkit.getTracer(instrumentationScopeName, statusMapper = StatusMapper.default)
          _             <- ZIO.unit @@ tracer.aspects.span("default-ok")
          _             <- (
                             ZIO.fail(new RuntimeException("Error")) @@
                               tracer.aspects.span("default-failed")
                           ).either
          spans         <- tracerTestkit.getFinishedSpans
          defaultOk      = spans.find(_.name == "default-ok")
          defaultFailed  = spans.find(_.name == "default-failed")
        } yield assert(defaultOk)(isSome(assertDefaultOk)) && assert(defaultFailed)(
          isSome(assertDefaultFailed && assertErrorExceptionEmpty)
        )
      },
      test("defaultRecordingExceptions") {
        val assertOkStatusCode  = assertSpanStatusCode(equalTo(StatusCode.UNSET))
        val assertOkDescription = assertSpanDescription(equalTo(""))

        val assertFailedStatusCode  = assertSpanStatusCode(equalTo(StatusCode.ERROR))
        val assertFailedDescription = assertSpanDescription(equalTo(""))

        val assertDefaultOk     =
          assertOkStatusCode && assertOkDescription
        val assertDefaultFailed =
          assertFailedStatusCode && assertFailedDescription

        val assertErrorRuntimeException = assertSpanException(
          hasSubset(List("exception.message" -> "Error", "exception.type" -> "zio.FiberFailure"))
        )

        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          tracer        <-
            tracerTestkit.getTracer(instrumentationScopeName, statusMapper = StatusMapper.defaultRecordingExceptions)
          _             <- ZIO.unit @@ tracer.aspects.span("default-ok")
          _             <- (
                             ZIO.fail(new RuntimeException("Error")) @@
                               tracer.aspects.span("default-failed")
                           ).either
          spans         <- tracerTestkit.getFinishedSpans
          defaultOk      = spans.find(_.name == "default-ok")
          defaultFailed  = spans.find(_.name == "default-failed")
        } yield assert(defaultOk)(isSome(assertDefaultOk)) && assert(defaultFailed)(
          isSome(assertDefaultFailed && assertErrorRuntimeException)
        )
      },
      test("both") {
        val assertDefaultOkStatusCode  = assertSpanStatusCode(equalTo(StatusCode.UNSET))
        val assertDefaultOkDescription = assertSpanDescription(equalTo(""))

        val assertDefaultFailedStatusCode  = assertSpanStatusCode(equalTo(StatusCode.ERROR))
        val assertDefaultFailedDescription = assertSpanDescription(equalTo(""))

        val assertionNotDefaultOk     =
          (assertDefaultOkStatusCode && assertDefaultOkDescription).negate
        val assertionNotDefaultFailed =
          (assertDefaultFailedStatusCode && assertDefaultFailedDescription).negate

        val statusMapper =
          StatusMapper.both(
            StatusMapper.success[Unit](_ => StatusCode.OK)(_ => Some("OK")),
            StatusMapper.failureThrowable(_ => StatusCode.OK)
          )

        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          tracer        <- tracerTestkit.getTracer(instrumentationScopeName)
          _             <- ZIO.unit @@ tracer.aspects.span("default-ok", statusMapper = statusMapper)
          _             <- (
                             ZIO.fail(new RuntimeException("Error")) @@
                               tracer.aspects.span("default-failed", statusMapper = statusMapper)
                           ).either
          spans         <- tracerTestkit.getFinishedSpans
          defaultOk      = spans.find(_.name == "default-ok")
          defaultFailed  = spans.find(_.name == "default-failed")
        } yield assert(defaultOk)(isSome(assertionNotDefaultOk)) &&
          assert(defaultFailed)(isSome(assertionNotDefaultFailed))
      },
      test("success & successNoDescription") {
        val assertOkStatusCode  = assertSpanStatusCode(equalTo(StatusCode.OK))
        val assertOkDescription = assertSpanDescription(equalTo(""))

        val assertErrorStatusCode  = assertSpanStatusCode(equalTo(StatusCode.ERROR))
        val assertErrorDescription = assertSpanDescription(equalTo("Error"))

        val assertOk              =
          assertOkStatusCode && assertOkDescription
        val assertOkNoDescription =
          assertOkStatusCode && assertOkDescription
        val assertError           =
          assertErrorStatusCode && assertErrorDescription

        for {
          tracerTestkit  <- ZIO.service[TracerTestkit]
          tracer         <- tracerTestkit.getTracer(instrumentationScopeName)
          _              <-
            ZIO.unit @@
              tracer.aspects
                .span("ok", statusMapper = StatusMapper.success[Unit](_ => StatusCode.OK)(_ => Some("OK")))
          _              <-
            ZIO.unit @@
              tracer.aspects
                .span("ok-no-description", statusMapper = StatusMapper.successNoDescription[Unit](_ => StatusCode.OK))
          _              <-
            ZIO.unit @@
              tracer.aspects
                .span("error", statusMapper = StatusMapper.success[Unit](_ => StatusCode.ERROR)(_ => Some("Error")))
          spans          <- tracerTestkit.getFinishedSpans
          ok              = spans.find(_.name == "ok")
          okNoDescription = spans.find(_.name == "ok-no-description")
          error           = spans.find(_.name == "error")
        } yield assert(ok)(isSome(assertOk)) &&
          assert(okNoDescription)(isSome(assertOkNoDescription)) &&
          assert(error)(isSome(assertError))
      },
      test(
        "failure && failureNoException && failureNoDescription && failureCause && failureCauseNoException && failureCauseNoDescription"
      ) {
        val assertOkStatusCode     = assertSpanStatusCode(equalTo(StatusCode.OK))
        val assertOkDescription    = assertSpanDescription(equalTo(""))
        val assertOkExceptionEmpty = assertSpanException(isEmpty)
        val assertOkExceptionIsSet = assertSpanException(
          hasSubset(List("exception.message" -> "OK", "exception.type" -> "java.lang.RuntimeException"))
        )

        val assertErrorStatusCode       = assertSpanStatusCode(equalTo(StatusCode.ERROR))
        val assertErrorDescriptionEmpty = assertSpanDescription(equalTo(""))
        val assertErrorDescription      = assertSpanDescription(equalTo("Error"))
        val assertErrorExceptionEmpty   = assertSpanException(isEmpty)
        val assertErrorException        = assertSpanException(
          hasSubset(List("exception.message" -> "Error", "exception.type" -> "java.lang.RuntimeException"))
        )
        val assertErrorFiberFailure     = assertSpanException(
          hasSubset(List("exception.message" -> "Error(Error)", "exception.type" -> "zio.FiberFailure"))
        )
        val assertBoomRuntimeException  = assertSpanException(
          hasSubset(List("exception.message" -> "boom", "exception.type" -> "java.lang.RuntimeException"))
        )

        val assertOkNoException                 =
          assertOkStatusCode && assertOkDescription && assertOkExceptionEmpty
        val assertOkNoExceptionAndDescription   =
          assertOkStatusCode && assertOkDescription && assertOkExceptionEmpty
        val assertOkWithException               =
          assertOkStatusCode && assertOkDescription && assertOkExceptionIsSet
        val assertErrorNoException              =
          assertErrorStatusCode && assertErrorExceptionEmpty && assertErrorDescription
        val assertErrorNoExceptionNoDescription =
          assertErrorStatusCode && assertErrorExceptionEmpty && assertErrorDescriptionEmpty
        val assertErrorNoDescription            =
          assertErrorStatusCode && assertErrorException && assertErrorDescriptionEmpty
        val assertErrorCause                    =
          assertErrorStatusCode && assertErrorFiberFailure && assertErrorDescription
        val assertErrorCauseNoException         =
          assertErrorStatusCode && assertErrorExceptionEmpty && assertErrorDescription
        val assertErrorCauseNoDescription       =
          assertErrorStatusCode && assertErrorFiberFailure && assertErrorDescriptionEmpty
        val assertErrorRuntimeBoom              =
          assertErrorStatusCode && assertBoomRuntimeException && assertErrorDescription

        final case class Error(message: String)

        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          tracer        <- tracerTestkit.getTracer(instrumentationScopeName)
          _             <-
            (ZIO.fail(Error("OK")) @@
              tracer.aspects
                .span(
                  "ok-no-exception",
                  statusMapper = StatusMapper.failure[Error](_ => StatusCode.OK)(e => Some(e.message))(_ => None)
                )).either
          _             <-
            (ZIO.fail(Error("OK")) @@
              tracer.aspects
                .span(
                  "ok-no-exception-1",
                  statusMapper = StatusMapper.failureNoException[Error](_ => StatusCode.OK)(e => Some(e.message))
                )).either
          _             <-
            (ZIO.fail(Error("OK")) @@
              tracer.aspects
                .span(
                  "ok-no-exception-and-description",
                  statusMapper = StatusMapper.failureNoDescription[Error](_ => StatusCode.OK)(_ => None)
                )).either
          _             <-
            (ZIO.fail(Error("OK")) @@
              tracer.aspects.span(
                "ok-with-exception",
                statusMapper = StatusMapper.failureNoDescription[Error](_ => StatusCode.OK)(e =>
                  Some(new RuntimeException(e.message))
                )
              )).either
          _             <-
            (ZIO.fail(Error("Error")) @@
              tracer.aspects.span(
                "error-no-exception",
                statusMapper = StatusMapper.failureNoException[Error](_ => StatusCode.ERROR)(e => Some(e.message))
              )).either

          _ <-
            (ZIO.fail(Error("Error")) @@
              tracer.aspects.span(
                "error-no-description",
                statusMapper = StatusMapper.failure[Error](_ => StatusCode.ERROR)(_ => None)(e =>
                  Some(new RuntimeException(e.message))
                )
              )).either
          _ <-
            (ZIO.fail(Error("Error")) @@
              tracer.aspects.span(
                "error-no-description-1",
                statusMapper = StatusMapper.failureNoDescription[Error](_ => StatusCode.ERROR)(e =>
                  Some(new RuntimeException(e.message))
                )
              )).either
          _ <-
            (ZIO.fail(Error("Error")) @@
              tracer.aspects.span(
                "error-cause",
                statusMapper = StatusMapper.failureCause[Error](_ => StatusCode.ERROR)(_ => Some("Error"))(e =>
                  Some(FiberFailure(e))
                )
              )).either
          _ <-
            (ZIO.fail(Error("Error")) @@
              tracer.aspects.span(
                "error-cause-no-exception",
                statusMapper = StatusMapper.failureCauseNoException[Error](_ => StatusCode.ERROR)(_ => Some("Error"))
              )).either
          _ <-
            (ZIO.fail(Error("Error")) @@
              tracer.aspects.span(
                "error-cause-no-description",
                statusMapper =
                  StatusMapper.failureCauseNoDescription[Error](_ => StatusCode.ERROR)(e => Some(FiberFailure(e)))
              )).either
          _ <-
            (ZIO.die(new RuntimeException("boom")) @@
              tracer.aspects.span(
                "error-die",
                statusMapper = StatusMapper.failure[Error](_ => StatusCode.ERROR)(_ => Some("Error"))(e =>
                  Some(new RuntimeException(e.message))
                )
              )).sandbox.ignore
          _ <-
            (ZIO.die(new RuntimeException("boom")) @@
              tracer.aspects.span(
                "error-cause-die",
                statusMapper = StatusMapper.failureCause[Error](_ => StatusCode.ERROR)(_ => Some("Error"))(e =>
                  Some(new RuntimeException(FiberFailure(e).getMessage()))
                )
              )).sandbox.ignore

          spans                      <- tracerTestkit.getFinishedSpans
          okNoException               = spans.find(_.name == "ok-no-exception")
          okNoException1              = spans.find(_.name == "ok-no-exception-1")
          okNoExceptionAndDescription = spans.find(_.name == "ok-no-exception-and-description")
          okWithException             = spans.find(_.name == "ok-with-exception")
          errorNoException            = spans.find(_.name == "error-no-exception")
          errorNoDescription          = spans.find(_.name == "error-no-description")
          errorNoDescription1         = spans.find(_.name == "error-no-description-1")
          errorCause                  = spans.find(_.name == "error-cause")
          errorCauseNoException       = spans.find(_.name == "error-cause-no-exception")
          errorCauseNoDescription     = spans.find(_.name == "error-cause-no-description")
          errorDie                    = spans.find(_.name == "error-die")
          errorCauseDie               = spans.find(_.name == "error-cause-die")
        } yield assert(okNoException)(isSome(assertOkNoException)) &&
          assert(okNoException1)(isSome(assertOkNoException)) &&
          assert(okNoExceptionAndDescription)(isSome(assertOkNoExceptionAndDescription)) &&
          assert(okWithException)(isSome(assertOkWithException)) &&
          assert(errorNoException)(isSome(assertErrorNoException)) &&
          assert(errorNoDescription)(isSome(assertErrorNoDescription)) &&
          assert(errorNoDescription1)(isSome(assertErrorNoDescription)) &&
          assert(errorCause)(isSome(assertErrorCause)) &&
          assert(errorCauseNoException)(isSome(assertErrorCauseNoException)) &&
          assert(errorCauseNoDescription)(isSome(assertErrorCauseNoDescription)) &&
          // the non-cause version doesn't set exception nor description for defects
          assert(errorDie)(isSome(assertErrorNoExceptionNoDescription)) &&
          assert(errorCauseDie)(isSome(assertErrorRuntimeBoom))
      },
      test("failureThrowable") {
        val assertOkStatusCode  = assertSpanStatusCode(equalTo(StatusCode.OK))
        val assertOkDescription = assertSpanDescription(equalTo(""))
        val assertOkException   = assertSpanException(
          hasSubset(List("exception.message" -> "OK", "exception.type" -> "java.lang.RuntimeException"))
        )

        val assertErrorStatusCode  = assertSpanStatusCode(equalTo(StatusCode.ERROR))
        val assertErrorDescription = assertSpanDescription(equalTo(""))
        val assertErrorException   = assertSpanException(
          hasSubset(List("exception.message" -> "Error", "exception.type" -> "java.lang.RuntimeException"))
        )

        val assertOk    =
          assertOkStatusCode && assertOkDescription && assertOkException
        val assertError =
          assertErrorStatusCode && assertErrorException && assertErrorDescription

        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          tracer        <- tracerTestkit.getTracer(instrumentationScopeName)
          _             <-
            (ZIO.fail(new RuntimeException("OK")) @@
              tracer.aspects
                .span(
                  "ok",
                  statusMapper = StatusMapper.failureThrowable(_ => StatusCode.OK)
                )).either
          _             <-
            (ZIO.fail(new RuntimeException("Error")) @@
              tracer.aspects.span(
                "error",
                statusMapper = StatusMapper.failureThrowable(_ => StatusCode.ERROR)
              )).either

          spans <- tracerTestkit.getFinishedSpans
          ok     = spans.find(_.name == "ok")
          error  = spans.find(_.name == "error")
        } yield assert(ok)(isSome(assertOk)) &&
          assert(error)(isSome(assertError))
      }
    ).provide(TracerTestkit.inMemory, OpenTelemetryTestkit.ctxStorageZioFiberRef)

  private val spanWithLogAnnotationsSpec =
    suite("spans with log annotations")(
      test("with log annotations") {
        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          tracer        <- tracerTestkit.getTracer(instrumentationScopeName, logAnnotated = true)
          _             <- ZIO.logAnnotate("log-attribute", "foo") {
                             ZIO.unit @@ tracer.aspects.span(
                               "Root",
                               attributes = Attributes(Attribute.string("root-attribute", "bar"))
                             )
                           }
          spans         <- tracerTestkit.getFinishedSpans
          tags           = spans.head.attributes
        } yield assert(tags.get[String]("root-attribute"))(isSome(equalTo("bar"))) &&
          assert(tags.get[String]("log-attribute"))(isSome(equalTo("foo")))
      }.provide(TracerTestkit.inMemory, OpenTelemetryTestkit.ctxStorageZioFiberRef),
      test("span attributes override log annotated") {
        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          tracer        <- tracerTestkit.getTracer(instrumentationScopeName, logAnnotated = true)
          _             <- ZIO.logAnnotate("some-attribute", "foo") {
                             ZIO.unit @@ tracer.aspects.span(
                               "Root",
                               attributes = Attributes(Attribute.string("some-attribute", "bar"))
                             )
                           }
          spans         <- tracerTestkit.getFinishedSpans
          tags           = spans.head.attributes
        } yield assert(tags.get[String]("some-attribute"))(isSome(equalTo("bar")))
      }.provide(TracerTestkit.inMemory, OpenTelemetryTestkit.ctxStorageZioFiberRef),
      test("without log annotations") {
        for {
          tracerTestkit <- ZIO.service[TracerTestkit]
          tracer        <- tracerTestkit.getTracer(instrumentationScopeName)
          _             <- ZIO.logAnnotate("log-attribute", "foo") {
                             ZIO.unit @@ tracer.aspects.span(
                               "Root",
                               attributes = Attributes(Attribute.string("root-attribute", "bar"))
                             )
                           }
          spans         <- tracerTestkit.getFinishedSpans
          tags           = spans.head.attributes
        } yield assert(tags.get[String]("root-attribute"))(isSome(equalTo("bar"))) &&
          assert(tags.get[String]("log-attribute"))(isNone)
      }.provide(TracerTestkit.inMemory, OpenTelemetryTestkit.ctxStorageZioFiberRef)
    )
}
