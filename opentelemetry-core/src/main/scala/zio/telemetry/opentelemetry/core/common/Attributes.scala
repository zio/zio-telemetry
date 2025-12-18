package zio.telemetry.opentelemetry.core.common

import io.opentelemetry.api
import io.opentelemetry.api.common.AttributesBuilder

/**
 * Scala helpers to build [[io.opentelemetry.api.common.Attributes]]
 */
object Attributes {

  def empty: api.common.Attributes =
    api.common.Attributes.empty()

  private def putAttribute[T](builder: AttributesBuilder, attr: Attribute[T]): AttributesBuilder =
    builder.put(attr.key, attr.value)

  def fromList(attributes: List[Attribute[_]]): api.common.Attributes = {
    val builder = api.common.Attributes.builder()

    attributes.foreach(attr => putAttribute(builder, attr))
    builder.build()
  }

  def apply[T](attribute: Attribute[T]): api.common.Attributes =
    api.common.Attributes.of(attribute.key, attribute.value)

  def apply[T, U](attribute1: Attribute[T], attribute2: Attribute[U]): api.common.Attributes =
    api.common.Attributes.of(
      attribute1.key,
      attribute1.value,
      attribute2.key,
      attribute2.value
    )

  def apply[T, U, V](
    attribute1: Attribute[T],
    attribute2: Attribute[U],
    attribute3: Attribute[V]
  ): api.common.Attributes =
    api.common.Attributes.of(
      attribute1.key,
      attribute1.value,
      attribute2.key,
      attribute2.value,
      attribute3.key,
      attribute3.value
    )

  def apply[T, U, V, W](
    attribute1: Attribute[T],
    attribute2: Attribute[U],
    attribute3: Attribute[V],
    attribute4: Attribute[W]
  ): api.common.Attributes =
    api.common.Attributes.of(
      attribute1.key,
      attribute1.value,
      attribute2.key,
      attribute2.value,
      attribute3.key,
      attribute3.value,
      attribute4.key,
      attribute4.value
    )

  def apply[T, U, V, W, X](
    attribute1: Attribute[T],
    attribute2: Attribute[U],
    attribute3: Attribute[V],
    attribute4: Attribute[W],
    attribute5: Attribute[X]
  ): api.common.Attributes =
    api.common.Attributes.of(
      attribute1.key,
      attribute1.value,
      attribute2.key,
      attribute2.value,
      attribute3.key,
      attribute3.value,
      attribute4.key,
      attribute4.value,
      attribute5.key,
      attribute5.value
    )

  def apply[T, U, V, W, X, Y](
    attribute1: Attribute[T],
    attribute2: Attribute[U],
    attribute3: Attribute[V],
    attribute4: Attribute[W],
    attribute5: Attribute[X],
    attribute6: Attribute[Y]
  ): api.common.Attributes =
    api.common.Attributes.of(
      attribute1.key,
      attribute1.value,
      attribute2.key,
      attribute2.value,
      attribute3.key,
      attribute3.value,
      attribute4.key,
      attribute4.value,
      attribute5.key,
      attribute5.value,
      attribute6.key,
      attribute6.value
    )

}
