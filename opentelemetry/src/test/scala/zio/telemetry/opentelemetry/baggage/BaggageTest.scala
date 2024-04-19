package zio.telemetry.opentelemetry.baggage

import zio._
import zio.telemetry.opentelemetry.baggage.propagation.BaggagePropagator
import zio.telemetry.opentelemetry.context.{ContextStorage, IncomingContextCarrier, OutgoingContextCarrier}
import zio.test.Assertion._
import zio.test._

import scala.collection.mutable

object BaggageTest extends ZIOSpecDefault {

  def baggageLayer: ULayer[Baggage] =
    ContextStorage.fiberRef >>> Baggage.live()

  def logAnnotatedBaggageLayer: ULayer[Baggage] =
    (ContextStorage.fiberRef >>> Baggage.live(logAnnotated = true))

  def spec: Spec[Environment with TestEnvironment with Scope, Any] =
    suite("zio opentelemetry")(
      suite("Baggage")(
        operationsSpec,
        propagationSpec,
        logAnnotatedSpec
      )
    )

  private def operationsSpec =
    suite("operations")(
      test("set/get") {
        ZIO.serviceWithZIO[Baggage] { baggage =>
          for {
            _     <- baggage.set("some", "thing")
            value <- baggage.get("some")
          } yield assert(value)(isSome(equalTo("thing")))
        }
      }.provideLayer(baggageLayer),
      test("set/getAll with metadata") {
        ZIO.serviceWithZIO[Baggage] { baggage =>
          for {
            _      <- baggage.setWithMetadata("some", "thing", "meta")
            result <- baggage.getAllWithMetadata
          } yield assert(result)(equalTo(Map("some" -> ("thing" -> "meta"))))
        }
      }.provideLayer(baggageLayer),
      test("remove") {
        ZIO.serviceWithZIO[Baggage] { baggage =>
          for {
            _       <- baggage.set("some", "thing")
            thing   <- baggage.get("some")
            _       <- baggage.remove("some")
            noThing <- baggage.get("some")
          } yield assert(thing)(isSome(equalTo("thing"))) && assert(noThing)(isNone)
        }
      }.provideLayer(baggageLayer)
    )

  private def propagationSpec =
    suite("propagation")(
      test("inject/extract") {
        def setAndInject(): URIO[Baggage, Map[String, String]] =
          ZIO.serviceWithZIO[Baggage] { baggage =>
            val carrier = OutgoingContextCarrier.default()

            for {
              _ <- baggage.set("some", "thing")
              _ <- baggage.inject(BaggagePropagator.default, carrier)
            } yield carrier.kernel.toMap
          }

        def extractAndGet(extractCarrier: Map[String, String]): URIO[Baggage, Option[String]] =
          ZIO.serviceWithZIO[Baggage] { baggage =>
            for {
              _     <- baggage.extract(
                         BaggagePropagator.default,
                         IncomingContextCarrier.default(mutable.Map.empty ++ extractCarrier)
                       )
              thing <- baggage.get("some")
            } yield thing
          }

        for {
          carrier <- setAndInject().provideLayer(baggageLayer)
          thing   <- extractAndGet(carrier).provideLayer(baggageLayer)
        } yield assert(thing)(isSome(equalTo("thing")))
      }
    )

  private def logAnnotatedSpec =
    suite("log annotated")(
      test("get") {
        ZIO.serviceWithZIO[Baggage] { baggage =>
          ZIO.logAnnotate("zio", "annotation") {
            for {
              result <- baggage.get("zio")
            } yield assert(result)(isSome(equalTo("annotation")))
          }
        }
      },
      test("getAll") {
        ZIO.serviceWithZIO[Baggage] { baggage =>
          ZIO.logAnnotate(LogAnnotation("foo", "bar"), LogAnnotation("dog", "fox")) {
            for {
              result <- baggage.getAll
            } yield assert(result)(equalTo(Map("foo" -> "bar", "dog" -> "fox")))
          }
        }
      },
      test("set overrides a value of a key taken from log annotations") {
        ZIO.serviceWithZIO[Baggage] { baggage =>
          ZIO.logAnnotate(LogAnnotation("foo", "bar"), LogAnnotation("dog", "fox")) {
            for {
              _      <- baggage.set("some", "thing")
              _      <- baggage.set("foo", "bark")
              result <- baggage.getAll
            } yield assert(result)(equalTo(Map("foo" -> "bark", "dog" -> "fox", "some" -> "thing")))
          }
        }
      },
      test("remove doesn't work for keys provided by log annotations") {
        ZIO.serviceWithZIO[Baggage] { baggage =>
          ZIO.logAnnotate(LogAnnotation("foo", "bar")) {
            for {
              _      <- baggage.remove("foo")
              result <- baggage.getAll
            } yield assert(result)(equalTo(Map("foo" -> "bar")))
          }
        }
      },
      test("getAllWithMetadata returns a metadata provided by log annotations") {
        ZIO.serviceWithZIO[Baggage] { baggage =>
          ZIO.logAnnotate(LogAnnotation("foo", "bar")) {
            for {
              result <- baggage.getAllWithMetadata
            } yield assert(result)(equalTo(Map("foo" -> ("bar" -> "zio log annotation"))))
          }
        }
      },
      test("setWithMetadata overrides a value with metadata taken from log annotations") {
        ZIO.serviceWithZIO[Baggage] { baggage =>
          ZIO.logAnnotate(LogAnnotation("foo", "bar")) {
            for {
              _      <- baggage.setWithMetadata("foo", "bar", "baz")
              result <- baggage.getAllWithMetadata
            } yield assert(result)(equalTo(Map("foo" -> ("bar" -> "baz"))))
          }
        }
      }
    ).provideLayer(logAnnotatedBaggageLayer)

}
