package zio.opentelemetry.attributevalue

import io.opentelemetry.common.AttributeValue

trait AttributeValueConverter[A] {
  def convert(value: A): AttributeValue
}

object AttributeValueConverter {
  def toAttributeValue[A](value: A)(implicit c: AttributeValueConverter[A]): AttributeValue = c.convert(value)
}
