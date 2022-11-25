package zio.telemetry.opentelemetry.context

import io.opentelemetry.context.propagation.TextMapGetter
import zio.telemetry.opentelemetry.internal.ContextCarrier

import scala.jdk.CollectionConverters._
import java.lang
import scala.collection.mutable

trait IngoingContextCarrier[T] extends ContextCarrier[T] with TextMapGetter[T] {

  override def keys(carrier: T): lang.Iterable[String] =
    getAllKeys(carrier).asJava

  override def get(carrier: T, key: String): String =
    getByKey(carrier, key).orNull

  def getAllKeys(carrier: T): Iterable[String]

  def getByKey(carrier: T, key: String): Option[String]

}

object IngoingContextCarrier {

  def default(
    initial: mutable.Map[String, String] = mutable.Map.empty
  ): IngoingContextCarrier[mutable.Map[String, String]] =
    new IngoingContextCarrier[mutable.Map[String, String]] {

      override val kernel: mutable.Map[String, String] = initial

      override def getAllKeys(carrier: mutable.Map[String, String]): Iterable[String] =
        carrier.keys

      override def getByKey(carrier: mutable.Map[String, String], key: String): Option[String] =
        carrier.get(key)

    }

}
