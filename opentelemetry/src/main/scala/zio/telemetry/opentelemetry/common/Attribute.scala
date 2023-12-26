package zio.telemetry.opentelemetry.common

import io.opentelemetry.api.common.AttributeKey
import scala.jdk.CollectionConverters._

final case class Attribute[T](key: AttributeKey[T], value: T)

object Attribute {

  def string(key: String, value: String): Attribute[String] =
    Attribute(AttributeKey.stringKey(key), value)

  def boolean(key: String, value: Boolean): Attribute[java.lang.Boolean] =
    Attribute(AttributeKey.booleanKey(key), Boolean.box(value))

  def long(key: String, value: Long): Attribute[java.lang.Long] =
    Attribute(AttributeKey.longKey(key), Long.box(value))

  def double(key: String, value: Double): Attribute[java.lang.Double] =
    Attribute(AttributeKey.doubleKey(key), Double.box(value))

  def stringList(key: String, values: List[String]): Attribute[java.util.List[String]] =
    Attribute(AttributeKey.stringArrayKey(key), values.asJava)

  def booleanList(key: String, values: List[Boolean]): Attribute[java.util.List[java.lang.Boolean]] =
    Attribute(AttributeKey.booleanArrayKey(key), values.map(Boolean.box).asJava)

  def longList(key: String, values: List[Long]): Attribute[java.util.List[java.lang.Long]] =
    Attribute(AttributeKey.longArrayKey(key), values.map(Long.box).asJava)

  def doubleList(key: String, values: List[Double]): Attribute[java.util.List[java.lang.Double]] =
    Attribute(AttributeKey.doubleArrayKey(key), values.map(Double.box).asJava)

}
