package zio.opentelemetry

import io.opentelemetry.common.AttributeValue
import io.opentelemetry.context.propagation.HttpTextFormat
import io.opentelemetry.trace.Span
import zio.clock.Clock
import zio.opentelemetry.ContextPropagation.{ extractSpan, injectSpan }
import zio.opentelemetry.OpenTelemetry.{ createChildOf, currentSpan }
import zio.opentelemetry.attributevalue.AttributeValueConverter
import zio.opentelemetry.attributevalue.AttributeValueConverter.toAttributeValue
import zio.{ RIO, URIO, ZIO }

import scala.jdk.CollectionConverters._

object CurrentSpan {

  /**
   * Gets the current span.
   */
  def getCurrentSpan: ZIO[OpenTelemetry, Nothing, Span] = currentSpan.flatMap(_.get)

  /**
   * Sets the current span.
   */
  def setCurrentSpan(span: Span): ZIO[OpenTelemetry, Nothing, Unit] = currentSpan.flatMap(_.set(span))

  /**
   * Extracts the span from carrier `C` and set its child span with name 'spanName' as the current span.
   */
  def createChildFromExtracted[C](
    httpTextFormat: HttpTextFormat,
    carrier: C,
    reader: PropagationFormat.Reader[C],
    operation: String,
    spanKind: Span.Kind = Span.Kind.INTERNAL
  ): URIO[OpenTelemetry, Span] =
    for {
      extractedSpan <- extractSpan(httpTextFormat, carrier, reader)
      child         <- createChildOf(operation, extractedSpan, spanKind)
    } yield child

  /**
   * Injects the current span into carrier `C`
   */
  def injectCurrentSpan[C](
    httpTextFormat: HttpTextFormat,
    carrier: C,
    writer: PropagationFormat.Writer[C]
  ): RIO[OpenTelemetry, Unit] =
    for {
      current <- getCurrentSpan
      _       <- injectSpan(current, httpTextFormat, carrier, writer)
    } yield ()

  /**
   * Adds an event to the current span
   */
  def addEvent(name: String): URIO[OpenTelemetry with Clock, Unit] =
    for {
      nanoSeconds <- currentNanos
      span        <- getCurrentSpan
    } yield span.addEvent(name, nanoSeconds)

  /**
   * Adds an event with attributes to the current span.
   */
  def addEventWithAttributes(
    name: String,
    attributes: Map[String, AttributeValue]
  ): URIO[OpenTelemetry with Clock, Unit] =
    for {
      nanoSeconds <- currentNanos
      span        <- getCurrentSpan
    } yield span.addEvent(name, attributes.asJava, nanoSeconds)

  /**
   * Sets an attribute of the current span.
   */
  def setAttribute[A: AttributeValueConverter](name: String, value: A): URIO[OpenTelemetry with Clock, Unit] =
    getCurrentSpan.map(_.setAttribute(name, toAttributeValue(value)))

  /**
   * Ends the current span.
   */
  def endSpan: URIO[Clock with OpenTelemetry, Unit] =
    for {
      current <- getCurrentSpan
      _       <- SpanUtils.endSpan(current)
    } yield ()

}
