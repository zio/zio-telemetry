package zio.opentelemetry.tracing.attributevalue

import io.opentelemetry.common.AttributeValue

object AttributeValueConverterInstances {
  implicit val stringConverter: AttributeValueConverter[String]   = AttributeValue.stringAttributeValue(_)
  implicit val booleanConverter: AttributeValueConverter[Boolean] = AttributeValue.booleanAttributeValue(_)
  implicit val longConverter: AttributeValueConverter[Long]       = AttributeValue.longAttributeValue(_)
  implicit val doubleConverter: AttributeValueConverter[Double]   = AttributeValue.doubleAttributeValue(_)
  implicit val intConverter: AttributeValueConverter[Int]         = (value: Int) => longConverter.convert(value.toLong)
}
