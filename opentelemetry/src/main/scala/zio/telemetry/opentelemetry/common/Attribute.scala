package zio.telemetry.opentelemetry.common

import io.opentelemetry.api.common.AttributeKey

import scala.jdk.CollectionConverters._

/**
 * A Scala wrapper for the Java attribute key/value API. It lets you create key/value pairs without boxing Scala
 * primitives into Java ones.
 *
 * @param key
 *   key of the attribute
 * @param value
 *   value of the attribute
 * @tparam T
 *   Java-specific type `java.lang.*`
 */
final case class Attribute[T](key: AttributeKey[T], value: T)

/**
 * Constructors for [[Attribute]] values of each supported type.
 */
object Attribute {

  /**
   * Creates an attribute with a `String` value.
   */
  def string(key: String, value: String): Attribute[String] =
    Attribute(AttributeKey.stringKey(key), value)

  /**
   * Creates an attribute with a `Boolean` value.
   */
  def boolean(key: String, value: Boolean): Attribute[java.lang.Boolean] =
    Attribute(AttributeKey.booleanKey(key), Boolean.box(value))

  /**
   * Creates an attribute with a `Long` value.
   */
  def long(key: String, value: Long): Attribute[java.lang.Long] =
    Attribute(AttributeKey.longKey(key), Long.box(value))

  /**
   * Creates an attribute with a `Double` value.
   */
  def double(key: String, value: Double): Attribute[java.lang.Double] =
    Attribute(AttributeKey.doubleKey(key), Double.box(value))

  /**
   * Creates an attribute with a list of `String` values.
   */
  def stringList(key: String, values: List[String]): Attribute[java.util.List[String]] =
    Attribute(AttributeKey.stringArrayKey(key), values.asJava)

  /**
   * Creates an attribute with a list of `Boolean` values.
   */
  def booleanList(key: String, values: List[Boolean]): Attribute[java.util.List[java.lang.Boolean]] =
    Attribute(AttributeKey.booleanArrayKey(key), values.map(Boolean.box).asJava)

  /**
   * Creates an attribute with a list of `Long` values.
   */
  def longList(key: String, values: List[Long]): Attribute[java.util.List[java.lang.Long]] =
    Attribute(AttributeKey.longArrayKey(key), values.map(Long.box).asJava)

  /**
   * Creates an attribute with a list of `Double` values.
   */
  def doubleList(key: String, values: List[Double]): Attribute[java.util.List[java.lang.Double]] =
    Attribute(AttributeKey.doubleArrayKey(key), values.map(Double.box).asJava)

}
