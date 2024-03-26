package zio.telemetry.opentelemetry.metrics

import io.opentelemetry.api
import io.opentelemetry.api.common.Attributes
import zio._

/**
 * An instrument for observing measurements with values of type `A`
 *
 * @tparam A
 *   according to the specification, it can be either [[scala.Long]] or [[scala.Double]] type
 */
trait ObservableMeasurement[-A] {

  def record0(value: A, attributes: Attributes = Attributes.empty): Unit

  /**
   * Records a measurement.
   *
   * @param value
   *   measurement value
   * @param attributes
   *   set of attributes to associate with the value
   */
  def record(value: A, attributes: Attributes = Attributes.empty)(implicit trace: Trace): Task[Unit] =
    ZIO.succeed(record0(value, attributes))

}

object ObservableMeasurement {

  private[metrics] def long(om: api.metrics.ObservableLongMeasurement): ObservableMeasurement[Long] =
    new ObservableMeasurement[Long] {

      override def record0(value: Long, attributes: Attributes): Unit =
        om.record(value, attributes)

    }

  private[metrics] def double(om: api.metrics.ObservableDoubleMeasurement): ObservableMeasurement[Double] =
    new ObservableMeasurement[Double] {

      override def record0(value: Double, attributes: Attributes): Unit =
        om.record(value, attributes)

    }

}
