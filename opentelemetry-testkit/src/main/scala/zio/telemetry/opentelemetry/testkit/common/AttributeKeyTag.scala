package zio.telemetry.opentelemetry.testkit.common

import io.opentelemetry.api.common.AttributeKey
import scala.jdk.CollectionConverters._

sealed trait AttributeKeyTag[T]

object AttributeKeyTag {

  implicit case object StringTag      extends AttributeKeyTag[String]
  implicit case object BooleanTag     extends AttributeKeyTag[Boolean]
  implicit case object LongTag        extends AttributeKeyTag[Long]
  implicit case object DoubleTag      extends AttributeKeyTag[Double]
  implicit case object ListStringTag  extends AttributeKeyTag[List[String]]
  implicit case object ListBooleanTag extends AttributeKeyTag[List[Boolean]]
  implicit case object ListLongTag    extends AttributeKeyTag[List[Long]]
  implicit case object ListDoubleTag  extends AttributeKeyTag[List[Double]]

  private[testkit] def asAttributeKey[T](key: String)(implicit tag: AttributeKeyTag[T]): AttributeKey[_] =
    tag match {
      case StringTag      => AttributeKey.stringKey(key)
      case BooleanTag     => AttributeKey.booleanKey(key)
      case LongTag        => AttributeKey.longKey(key)
      case DoubleTag      => AttributeKey.doubleKey(key)
      case ListStringTag  => AttributeKey.stringArrayKey(key)
      case ListBooleanTag => AttributeKey.booleanArrayKey(key)
      case ListLongTag    => AttributeKey.longArrayKey(key)
      case ListDoubleTag  => AttributeKey.doubleArrayKey(key)
    }

  private[testkit] def asScala[T](value: Any)(implicit tag: AttributeKeyTag[T]): T =
    tag match {
      case StringTag      =>
        value.asInstanceOf[String]
      case BooleanTag     =>
        Boolean.unbox(value.asInstanceOf[java.lang.Boolean])
      case LongTag        =>
        Long.unbox(value.asInstanceOf[java.lang.Long])
      case DoubleTag      =>
        Double.unbox(value.asInstanceOf[java.lang.Double])
      case ListStringTag  =>
        value
          .asInstanceOf[java.util.List[_]]
          .asScala
          .toList
          .map(_.asInstanceOf[String])
      case ListBooleanTag =>
        value
          .asInstanceOf[java.util.List[_]]
          .asScala
          .toList
          .map(v => Boolean.unbox(v.asInstanceOf[java.lang.Boolean]))
      case ListLongTag    =>
        value
          .asInstanceOf[java.util.List[_]]
          .asScala
          .toList
          .map(v => Long.unbox(v.asInstanceOf[java.lang.Long]))
      case ListDoubleTag  =>
        value
          .asInstanceOf[java.util.List[_]]
          .asScala
          .toList
          .map(v => Double.unbox(v.asInstanceOf[java.lang.Double]))
    }

}
