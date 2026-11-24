package zio.telemetry.opentelemetry.common

import io.opentelemetry.api

/**
 * Scala helpers to build [[io.opentelemetry.api.common.Attributes]]
 */
object Attributes {

  /**
   * Returns an empty set of attributes.
   */
  def empty: api.common.Attributes =
    api.common.Attributes.empty()

  /**
   * Builds a set of attributes from a list of [[Attribute]] values.
   *
   * @param attributes
   *   attributes to put in the set
   */
  def fromList[T](attributes: List[Attribute[T]]): api.common.Attributes = {
    val builder = api.common.Attributes.builder()

    attributes.foreach(attr => builder.put(attr.key, attr.value))
    builder.build()
  }

  /**
   * Builds a set with one attribute.
   */
  def apply[T](attribute: Attribute[T]): api.common.Attributes =
    api.common.Attributes.of(attribute.key, attribute.value)

  /**
   * Builds a set with two attributes.
   */
  def apply[T, U](attribute1: Attribute[T], attribute2: Attribute[U]): api.common.Attributes =
    api.common.Attributes.of(
      attribute1.key,
      attribute1.value,
      attribute2.key,
      attribute2.value
    )

  /**
   * Builds a set with three attributes.
   */
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

  /**
   * Builds a set with four attributes.
   */
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

  /**
   * Builds a set with five attributes.
   */
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

  /**
   * Builds a set with six attributes.
   */
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
