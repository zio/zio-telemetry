package zio.telemetry.opentelemetry.metrics.internal

import zio._
import zio.metrics.{MetricKey, MetricKeyType}
import zio.telemetry.opentelemetry.metrics.{Counter, Histogram}

import java.util.concurrent.ConcurrentHashMap

trait InstrumentRegistry {

  def getCounter(key: MetricKey.Counter): Counter[Long]

  def getHistorgram(key: MetricKey.Histogram): Histogram[Double]

  def getGauge(key: MetricKey.Gauge): AtomicDouble

}

object InstrumentRegistry {

  def concurrent: URLayer[Instrument.Builder, InstrumentRegistry] =
    ZLayer(
      for {
        builder <- ZIO.service[Instrument.Builder]
      } yield new InstrumentRegistry {

        val map: ConcurrentHashMap[MetricKey[MetricKeyType], Instrument[_]] =
          new ConcurrentHashMap[MetricKey[MetricKeyType], Instrument[_]]()

        val gauges: ConcurrentHashMap[MetricKey.Gauge, AtomicDouble] =
          new ConcurrentHashMap[MetricKey.Gauge, AtomicDouble]()

        def getCounter(key: MetricKey.Counter): Counter[Long] =
          getOrCreateInstrument[Counter, Long](key)(builder.counter(key.name, description = key.description))

        def getHistorgram(key: MetricKey.Histogram): Histogram[Double] =
          getOrCreateInstrument[Histogram, Double](key)(builder.histogram(key.name, description = key.description))

        def getGauge(key: MetricKey.Gauge): AtomicDouble =
          gauges.computeIfAbsent(
            key,
            { gaugeKey =>
              val ref = AtomicDouble(0)

              val _ = builder.observableGauge(gaugeKey.name, description = gaugeKey.description) { om =>
                om.record0(ref.get(), attributes(gaugeKey.tags))
              }

              ref
            }
          )

        private def getOrCreateInstrument[I[_] <: Instrument[_], A](
          key: MetricKey[MetricKeyType]
        )(build: => I[A]): I[A] = {
          var value = map.get(key)

          if (value eq null) {
            val counter = build
            map.putIfAbsent(key, counter)
            value = map.get(key)
          }

          value.asInstanceOf[I[A]]
        }

      }
    )

}
