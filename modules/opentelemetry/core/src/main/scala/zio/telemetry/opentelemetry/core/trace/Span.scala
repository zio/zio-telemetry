package zio.telemetry.opentelemetry.core.trace

import io.opentelemetry.api.common.{AttributeKey, Attributes}
import io.opentelemetry.api.trace.{Span => JSpan, SpanContext, StatusCode}
import io.opentelemetry.context.Context
import zio._
import zio.telemetry.opentelemetry.core.common.Attribute

import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters._

trait Span { self =>

  def context: SpanContext

  /**
   * Adds an event to the current span.
   *
   * @param name
   * @param trace
   * @return
   */
  def addEvent(name: String)(implicit trace: Trace): UIO[Unit]

  /**
   * Adds an event with attributes to the current span.
   *
   * @param name
   * @param attributes
   *   event attributes
   * @param trace
   * @return
   */
  def addEventWithAttributes(
    name: String,
    attributes: Attributes
  )(implicit trace: Trace): UIO[Unit]

  /**
   * Sets an attribute of the current span.
   *
   * @param name
   * @param value
   * @param trace
   * @return
   */
  def setAttribute(name: String, value: Boolean)(implicit trace: Trace): UIO[Unit]

  /**
   * Sets an attribute of the current span.
   *
   * @param name
   * @param value
   * @param trace
   * @return
   */
  def setAttribute(name: String, value: Double)(implicit trace: Trace): UIO[Unit]

  /**
   * Sets an attribute of the current span.
   *
   * @param name
   * @param value
   * @param trace
   * @return
   */
  def setAttribute(name: String, value: Long)(implicit trace: Trace): UIO[Unit]

  /**
   * Sets an attribute of the current span.
   *
   * @param name
   * @param value
   * @param trace
   * @return
   */
  def setAttribute(name: String, value: String)(implicit trace: Trace): UIO[Unit]

  /**
   * Sets an attribute of the current span.
   *
   * @param key
   * @param value
   * @param trace
   * @tparam T
   * @return
   */
  def setAttribute[T](key: AttributeKey[T], value: T)(implicit trace: Trace): UIO[Unit]

  /**
   * Sets an attribute of the current span.
   *
   * @param attribute
   *   convenient Scala wrapper for Java key/value
   * @param trace
   */
  def setAttribute[T](attribute: Attribute[T])(implicit trace: Trace): UIO[Unit]

  /**
   * Sets an attribute of the current span.
   *
   * @param name
   * @param values
   * @param trace
   * @return
   */
  def setAttribute(name: String, values: Seq[String])(implicit trace: Trace): UIO[Unit]

  /**
   * Sets an attribute of the current span.
   *
   * @param name
   * @param values
   * @param i1
   *   dummy implicit value to disambiguate the method calls
   * @param trace
   * @return
   */
  def setAttribute(name: String, values: Seq[Boolean])(implicit i1: DummyImplicit, trace: Trace): UIO[Unit]

  /**
   * Sets an attribute of the current span.
   *
   * @param name
   * @param values
   * @param i1
   *   dummy implicit value to disambiguate the method calls
   * @param i2
   *   dummy implicit value to disambiguate the method calls
   * @param trace
   * @return
   */
  def setAttribute(name: String, values: Seq[Long])(implicit
    i1: DummyImplicit,
    i2: DummyImplicit,
    trace: Trace
  ): UIO[Unit]

  /**
   * Sets an attribute of the current span.
   *
   * @param name
   * @param values
   * @param i1
   *   dummy implicit value to disambiguate the method calls
   * @param i2
   *   dummy implicit value to disambiguate the method calls
   * @param i3
   *   dummy implicit value to disambiguate the method calls
   * @param trace
   * @return
   */
  def setAttribute(name: String, values: Seq[Double])(implicit
    i1: DummyImplicit,
    i2: DummyImplicit,
    i3: DummyImplicit,
    trace: Trace
  ): UIO[Unit]

  def setAllAttributes(attributes: Attributes)(implicit trace: Trace): UIO[Unit]

  def setStatus(statusCode: StatusCode)(implicit trace: Trace): UIO[Unit]

  def setStatus(statusCode: StatusCode, description: String)(implicit trace: Trace): UIO[Unit]

  def recordException(exception: Throwable)(implicit trace: Trace): UIO[Unit]

  def end(implicit trace: Trace): UIO[Unit]

  def end(timestamp: Long, unit: TimeUnit)(implicit trace: Trace): UIO[Unit]

  trait UnsafeAPI {
    def asJava: JSpan
  }

  val unsafe: UnsafeAPI

}

private[opentelemetry] object Span {

  def fromContext(ctx: Context): Span =
    make(JSpan.fromContext(ctx))

  def make(underlying: JSpan): Span =
    new Span {

      override def context: SpanContext =
        underlying.getSpanContext

      override def addEvent(name: String)(implicit trace: Trace): UIO[Unit] =
        for {
          nanos <- currentNanos
          _     <- ZIO.succeed(underlying.addEvent(name, nanos, TimeUnit.NANOSECONDS))
        } yield ()

      override def addEventWithAttributes(name: String, attributes: Attributes)(implicit trace: Trace): UIO[Unit] =
        for {
          nanos <- currentNanos
          _     <- ZIO.succeed(underlying.addEvent(name, attributes, nanos, TimeUnit.NANOSECONDS))
        } yield ()

      override def setAttribute(name: String, value: Boolean)(implicit trace: Trace): UIO[Unit] =
        ZIO.succeed(underlying.setAttribute(name, value)).unit

      override def setAttribute(name: String, value: Double)(implicit trace: Trace): UIO[Unit] =
        ZIO.succeed(underlying.setAttribute(name, value)).unit

      override def setAttribute(name: String, value: Long)(implicit trace: Trace): UIO[Unit] =
        ZIO.succeed(underlying.setAttribute(name, value)).unit

      override def setAttribute(name: String, value: String)(implicit trace: Trace): UIO[Unit] =
        ZIO.succeed(underlying.setAttribute(name, value)).unit

      override def setAttribute[T](key: AttributeKey[T], value: T)(implicit trace: Trace): UIO[Unit] =
        ZIO.succeed(underlying.setAttribute(key, value)).unit

      override def setAttribute[T](attribute: Attribute[T])(implicit trace: Trace): UIO[Unit] =
        ZIO.succeed(underlying.setAttribute(attribute.key, attribute.value)).unit

      override def setAttribute(name: String, values: Seq[String])(implicit trace: Trace): UIO[Unit] = {
        val v = values.asJava
        ZIO.succeed(underlying.setAttribute(AttributeKey.stringArrayKey(name), v)).unit
      }

      override def setAttribute(name: String, values: Seq[Boolean])(implicit
        i1: DummyImplicit,
        trace: Trace
      ): UIO[Unit] = {
        val v = values.map(Boolean.box).asJava
        ZIO.succeed(underlying.setAttribute(AttributeKey.booleanArrayKey(name), v)).unit
      }

      override def setAttribute(name: String, values: Seq[Long])(implicit
        i1: DummyImplicit,
        i2: DummyImplicit,
        trace: Trace
      ): UIO[Unit] = {
        val v = values.map(Long.box).asJava
        ZIO.succeed(underlying.setAttribute(AttributeKey.longArrayKey(name), v)).unit
      }

      override def setAttribute(name: String, values: Seq[Double])(implicit
        i1: DummyImplicit,
        i2: DummyImplicit,
        i3: DummyImplicit,
        trace: Trace
      ): UIO[Unit] = {
        val v = values.map(Double.box).asJava
        ZIO.succeed(underlying.setAttribute(AttributeKey.doubleArrayKey(name), v)).unit
      }

      override def setAllAttributes(attributes: Attributes)(implicit trace: Trace): UIO[Unit] =
        ZIO.succeed(underlying.setAllAttributes(attributes)).unit

      override def setStatus(statusCode: StatusCode)(implicit trace: Trace): UIO[Unit] =
        ZIO.succeed(underlying.setStatus(statusCode)).unit

      override def setStatus(statusCode: StatusCode, description: String)(implicit trace: Trace): UIO[Unit] =
        ZIO.succeed(underlying.setStatus(statusCode, description)).unit

      override def recordException(exception: Throwable)(implicit trace: Trace): UIO[Unit] =
        ZIO.succeed(underlying.recordException(exception)).unit

      override def end(implicit trace: Trace): UIO[Unit] =
        ZIO.succeed(underlying.end())

      override def end(timestamp: Long, unit: TimeUnit)(implicit trace: Trace): UIO[Unit] =
        ZIO.succeed(underlying.end(timestamp, unit))

      override val unsafe: UnsafeAPI =
        new UnsafeAPI {
          override def asJava: JSpan =
            underlying
        }

      // TODO: deduplicate. See Tracer.currentNanos
      private def currentNanos(implicit trace: Trace): UIO[Long] =
        Clock.currentTime(TimeUnit.NANOSECONDS)
    }

}
