package zio.telemetry.opentelemetry.baggage

import io.opentelemetry.api.baggage.{Baggage => JBaggage, BaggageBuilder, BaggageEntry, BaggageEntryMetadata}
import zio._
import zio.telemetry.opentelemetry.context.internal.ContextStorage

import scala.jdk.CollectionConverters._

trait Baggage { self =>

  /**
   * Gets the value by a given name.
   *
   * @param name
   * @param trace
   * @return
   *   value
   */
  def get(name: String)(implicit trace: Trace): UIO[Option[String]]

  /**
   * Gets all values.
   *
   * @param trace
   * @return
   *   all values
   */
  def getAll(implicit trace: Trace): UIO[Map[String, String]]

  /**
   * Gets all values accompanied by metadata.
   *
   * @param trace
   * @return
   */
  def getAllWithMetadata(implicit trace: Trace): UIO[Map[String, (String, String)]]

  /**
   * Removes the name/value by a given name.
   *
   * @param name
   * @param trace
   * @return
   */
  def remove[R, E, A](name: String)(zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]

  /**
   * Sets the new value for a given name.
   *
   * @param name
   * @param value
   * @param trace
   * @return
   */
  def set[R, E, A](
    name: String,
    value: String
  )(zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]

  /**
   * Sets the new value and metadata for a given name.
   *
   * @param name
   * @param value
   * @param metadata
   *   opaque string
   * @param trace
   * @return
   */
  def setWithMetadata[R, E, A](
    name: String,
    value: String,
    metadata: String
  )(zio: => ZIO[R, E, A])(implicit
    trace: Trace
  ): ZIO[R, E, A]

  trait UnsafeAPI {
    def asJava(implicit trace: Trace): UIO[JBaggage]
  }

  val unsafe: UnsafeAPI

  object aspects {

    def remove(name: String): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
      new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
        override def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          self.remove(name)(zio)
      }

    def set(name: String, value: String): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
      new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
        override def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          self.set(name, value)(zio)
      }

    def setWithMetadata(
      name: String,
      value: String,
      metadata: String
    ): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
      new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
        override def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          self.setWithMetadata(name, value, metadata)(zio)
      }

  }

}

private[opentelemetry] object Baggage {

  def make(ctxStorage: ContextStorage, logAnnotated: Boolean = false): Baggage =
    new Baggage { self =>
      override def get(name: String)(implicit trace: Trace): UIO[Option[String]] =
        unsafe.asJava.map(baggage => Option(baggage.getEntryValue(name)))

      override def getAll(implicit trace: Trace): UIO[Map[String, String]] =
        unsafe.asJava.map(asScalaMap(_).map { case (k, v) => k -> v.getValue })

      override def getAllWithMetadata(implicit trace: Trace): UIO[Map[String, (String, String)]] =
        unsafe.asJava.map(
          asScalaMap(_).map { case (k, v) => (k, (v.getValue, v.getMetadata.getValue)) }
        )

      override def set[R, E, A](
        name: String,
        value: String
      )(zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
        modifyBuilder(_.put(name, value))(zio)

      override def setWithMetadata[R, E, A](
        name: String,
        value: String,
        metadata: String
      )(zio: => ZIO[R, E, A])(implicit
        trace: Trace
      ): ZIO[R, E, A] =
        modifyBuilder(_.put(name, value, BaggageEntryMetadata.create(metadata)))(zio)

      override def remove[R, E, A](name: String)(zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
        modifyBuilder(_.remove(name))(zio)

      private def modifyBuilder[R, E, A](
        f: BaggageBuilder => BaggageBuilder
      )(zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
        for {
          ctx       <- ctxStorage.get
          baggage   <- unsafe.asJava
          updatedCtx = f(baggage.toBuilder)
                         .build()
                         .storeInContext(ctx)
          result    <- ctxStorage.locally(updatedCtx)(zio)
        } yield result

      private def withLogAnnotations(baggage: JBaggage)(implicit trace: Trace): UIO[JBaggage] =
        if (logAnnotated) {
          ZIO.logAnnotations.map { annotations =>
            val annotationsWithMetadata = annotations.map { case (k, v) =>
              (k, (v, BaggageEntryMetadata.create("zio log annotation")))
            }
            val currentWithMetadata     = asScalaMap(baggage).map { case (k, v) => (k, (v.getValue, v.getMetadata)) }
            val merged                  = annotationsWithMetadata ++ currentWithMetadata
            val builder                 = baggage.toBuilder

            merged.foreach { case (k, (v, m)) => builder.put(k, v, m) }
            builder.build
          }
        } else ZIO.succeed(baggage)

      override val unsafe: UnsafeAPI =
        new UnsafeAPI {
          override def asJava(implicit trace: Trace): UIO[JBaggage] =
            for {
              ctx       <- ctxStorage.get
              baggage    = JBaggage.fromContext(ctx)
              annotated <- withLogAnnotations(baggage)
            } yield annotated
        }

      private def asScalaMap(baggage: JBaggage): Map[String, BaggageEntry] =
        baggage.asMap().asScala.toMap

    }

}
