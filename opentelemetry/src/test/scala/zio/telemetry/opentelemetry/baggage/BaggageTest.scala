package zio.telemetry.opentelemetry.baggage

import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator
import zio._
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.tracing.TextMapAdapter
import zio.test._
import zio.test.Assertion._

import scala.collection.mutable

object BaggageTest extends ZIOSpecDefault {

  def baggageLayer: ULayer[Baggage] =
    ContextStorage.fiberRef >>> Baggage.live

  def operationsSpec =
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

  def propagationSpec =
    suite("propagation")(
      test("inject/extract") {
        val propagator    = W3CBaggagePropagator.getInstance()
        val injectCarrier = mutable.Map.empty[String, String]

        def setAndInject: URIO[Baggage, Map[String, String]] =
          ZIO.serviceWithZIO[Baggage] { baggage =>
            for {
              _ <- baggage.set("some", "thing")
              _ <- baggage.inject(propagator, injectCarrier, TextMapAdapter)
            } yield injectCarrier.toMap
          }

        def extractAndGet(extractCarrier: Map[String, String]): URIO[Baggage, Option[String]] =
          ZIO.serviceWithZIO[Baggage] { baggage =>
            for {
              _     <- baggage.extract(propagator, mutable.Map.empty ++ extractCarrier, TextMapAdapter)
              thing <- baggage.get("some")
            } yield thing
          }

        for {
          carrier <- setAndInject.provideLayer(baggageLayer)
          thing   <- extractAndGet(carrier).provideLayer(baggageLayer)
        } yield assert(thing)(isSome(equalTo("thing")))
      }
    )

  def spec =
    suite("zio opentelemetry")(
      suite("Baggage")(
        operationsSpec,
        propagationSpec
      )
    )

}
