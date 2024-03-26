package zio.telemetry.opentelemetry.metrics.internal

import java.util.concurrent.atomic.AtomicLong

final class AtomicDouble(val ref: AtomicLong) extends AnyVal {

  def get(): Double =
    java.lang.Double.longBitsToDouble(ref.get())

  def set(newValue: Double): Unit =
    ref.set(java.lang.Double.doubleToLongBits(newValue))

  def compareAndSet(expected: Double, newValue: Double): Boolean =
    ref.compareAndSet(java.lang.Double.doubleToLongBits(expected), java.lang.Double.doubleToLongBits(newValue))

  def incrementBy(value: Double): Unit = {
    var loop = true

    while (loop) {
      val current = get()
      loop = !compareAndSet(current, current + value)
    }
  }
}

object AtomicDouble {

  def apply(value: Double): AtomicDouble =
    new AtomicDouble(new AtomicLong(java.lang.Double.doubleToLongBits(value)))

}
