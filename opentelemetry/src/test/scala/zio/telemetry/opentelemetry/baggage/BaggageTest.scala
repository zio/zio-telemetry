package zio.telemetry.opentelemetry.baggage

import zio._
import zio.telemetry.opentelemetry.baggage.propagation.BaggagePropagator
import zio.telemetry.opentelemetry.context.{ ContextStorage, IngoingContextCarrier, OutgoingContextCarrier }
import zio.test._
import zio.test.Assertion._

import scala.collection.mutable

object BaggageTest extends ZIOSpecDefault {

  def baggageLayer: ULayer[Baggage] =
    ContextStorage.fiberRef >>> Baggage.live

  def spec =
    suite("zio opentelemetry")(
      suite("Baggage")(
        operationsSpec,
        propagationSpec
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
        val injectCarrier = mutable.Map.empty[String, String]

        def setAndInject: URIO[Baggage, Map[String, String]] =
          ZIO.serviceWithZIO[Baggage] { baggage =>
            for {
              _ <- baggage.set("some", "thing")
              _ <- baggage.inject(BaggagePropagator.default, OutgoingContextCarrier.default(injectCarrier))
            } yield injectCarrier.toMap
          }

        def extractAndGet(extractCarrier: Map[String, String]): URIO[Baggage, Option[String]] =
          ZIO.serviceWithZIO[Baggage] { baggage =>
            for {
              _     <- baggage.extract(
                         BaggagePropagator.default,
                         IngoingContextCarrier.default(mutable.Map.empty ++ extractCarrier)
                       )
              thing <- baggage.get("some")
            } yield thing
          }

        for {
          carrier <- setAndInject.provideLayer(baggageLayer)
          thing   <- extractAndGet(carrier).provideLayer(baggageLayer)
        } yield assert(thing)(isSome(equalTo("thing")))
      }
    )

}
