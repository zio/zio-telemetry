package zio.telemetry.opencensus

import io.opencensus.trace.AttributeValue

object Attributes {

  trait implicits {
    implicit def boolToAttribute(b: Boolean): AttributeValue =
      AttributeValue.booleanAttributeValue(b)

    implicit def stringToAttribute(s: String): AttributeValue =
      AttributeValue.stringAttributeValue(s)

    implicit def longToAttribute(l: Long): AttributeValue =
      AttributeValue.longAttributeValue(l)

    implicit def doubleToAttribute(d: Double): AttributeValue =
      AttributeValue.doubleAttributeValue(d)
  }

}
