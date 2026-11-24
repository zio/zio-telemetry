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

  /**
   * Records a measurement immediately. Unlike [[record]], it does not return a `ZIO`.
   *
   * @param value
   *   measurement value
   * @param attributes
   *   set of attributes to associate with the value
   */
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

/**
 * Constructors for [[ObservableMeasurement]].
 */
object ObservableMeasurement {

  private[metrics] def long(om: api.metrics.ObservableLongMeasurement): ObservableMeasurement[Long] =
    make[Long](om.record(_, _))

  private[metrics] def double(om: api.metrics.ObservableDoubleMeasurement): ObservableMeasurement[Double] =
    make[Double](om.record(_, _))

  private def make[A](recordValue: (A, Attributes) => Unit): ObservableMeasurement[A] =
    new ObservableMeasurement[A] {
      override def record0(value: A, attributes: Attributes): Unit =
        recordValue(value, attributes)
    }

}
