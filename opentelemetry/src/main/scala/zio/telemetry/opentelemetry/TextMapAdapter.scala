package zio.telemetry.opentelemetry

import io.opentelemetry.context.propagation.{ TextMapGetter, TextMapSetter }

import java.lang
import scala.collection.mutable
import scala.jdk.CollectionConverters._

object TextMapAdapter
    extends TextMapGetter[mutable.Map[String, String]]
    with TextMapSetter[mutable.Map[String, String]] {

  override def keys(carrier: mutable.Map[String, String]): lang.Iterable[String] =
    carrier.keys.asJava

  override def get(carrier: mutable.Map[String, String], key: String): String =
    carrier.get(key).orNull

  override def set(carrier: mutable.Map[String, String], key: String, value: String): Unit =
    carrier.update(key, value)

}
