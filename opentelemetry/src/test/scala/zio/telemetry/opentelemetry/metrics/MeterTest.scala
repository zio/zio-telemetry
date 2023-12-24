package zio.telemetry.opentelemetry.metrics

import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import zio._
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.test.{TestEnvironment, ZIOSpecDefault, _}

import scala.jdk.CollectionConverters._

object MeterTest extends ZIOSpecDefault {

  val inMemoryMetricReaderLayer: ZLayer[Any, Nothing, InMemoryMetricReader] =
    ZLayer(ZIO.succeed(InMemoryMetricReader.create()))

  val meterLayer = {
    val jmeter = ZLayer {
      for {
        metricReader  <- ZIO.service[InMemoryMetricReader]
        meterProvider <- ZIO.succeed(SdkMeterProvider.builder().registerMetricReader(metricReader).build())
        meter         <- ZIO.succeed(meterProvider.get("MeterTest"))
      } yield meter
    }

    jmeter >>> Meter.live
  }

  override def spec: Spec[TestEnvironment with Scope, Any] =
    suite("zio opentelemetry")(
      suite("Meter")(
        test("observableCounter") {
          ZIO.serviceWithZIO[Meter] { meter =>
            for {
              reader <- ZIO.service[InMemoryMetricReader]
              _      <- meter
                          .observableCounter("obs")(_.record(1L))
                          .launch
                          .fork
              _      <- TestClock.adjust(1.millisecond)
              metrics = reader.collectAllMetrics().asScala.toList
            } yield assertTrue(metrics.nonEmpty)
          }
        }
      )
    ).provide(inMemoryMetricReaderLayer, meterLayer, ContextStorage.fiberRef)

}
