package zio.telemetry.opentelemetry.context

import io.opentelemetry.context.propagation.TextMapGetter
import zio.telemetry.opentelemetry.internal.ContextCarrier

import java.lang
import scala.collection.mutable
import scala.jdk.CollectionConverters._

/**
 * The wrapper for the context data from the external process.
 *
 * @tparam T
 */
trait IncomingContextCarrier[T] extends ContextCarrier[T] with TextMapGetter[T] {

  override def keys(carrier: T): lang.Iterable[String] =
    getAllKeys(carrier).asJava

  override def get(carrier: T, key: String): String =
    getByKey(carrier, key).orNull

  /**
   * Scala API for the [[keys]] method.
   *
   * @param carrier
   *   the context data
   * @return
   *   all the keys saved in the current context
   */
  def getAllKeys(carrier: T): Iterable[String]

  /**
   * Scala API for the [[get]] method.
   *
   * @param carrier
   *   the context data
   * @param key
   * @return
   *   optional value by the given key saved in the current context
   */
  def getByKey(carrier: T, key: String): Option[String]

}

object IncomingContextCarrier {

  /**
   * Default implementation of the [[IncomingContextCarrier]] where the type of the [[IncomingContextCarrier.kernel]] is
   * a mutable `Map[String, String]`.
   *
   * @param initial
   *   initial kernel
   * @return
   */
  def default(
    initial: mutable.Map[String, String] = mutable.Map.empty
  ): IncomingContextCarrier[mutable.Map[String, String]] =
    new IncomingContextCarrier[mutable.Map[String, String]] {

      override val kernel: mutable.Map[String, String] = initial

      override def getAllKeys(carrier: mutable.Map[String, String]): Iterable[String] =
        carrier.keys

      override def getByKey(carrier: mutable.Map[String, String], key: String): Option[String] =
        carrier.get(key)

    }

}
