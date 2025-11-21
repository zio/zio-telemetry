package zio.telemetry.opentelemetry.testkit.common

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.{Attributes => JAttributes}
import scala.jdk.CollectionConverters._

final case class Attributes(
  private val data: Map[AttributeKey[_], Any]
) {

  def get[T](key: String)(implicit tag: AttributeKeyTag[T]): Option[T] =
    data
      .get(AttributeKeyTag.asAttributeKey[T](key))
      .map(value => AttributeKeyTag.asScala(value))

  def asMap: Map[String, String] =
    data.map { case (k, v) => k.getKey -> v.toString }

  def isEmpty: Boolean =
    data.isEmpty

}

object Attributes {

  def apply(underlying: JAttributes): Attributes = {
    val map = underlying.asMap.asScala.toMap[AttributeKey[_], Any]

    Attributes(map)
  }

}
