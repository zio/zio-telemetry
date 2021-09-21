package zio.telemetry

import io.opentracing.propagation.Format
import zio.{ Has, ZIO }

package object opentracing {
  type OpenTracing = Has[OpenTracing.Service]

  implicit final class OpenTracingZioOps[-R, +E, +A](val zio: ZIO[R, E, A]) extends AnyVal {

    def root(
      operation: String,
      tagError: Boolean = true,
      logError: Boolean = true
    ): ZIO[R with OpenTracing, E, A] =
      OpenTracing.root(zio, operation, tagError, logError)

    def span(
      operation: String,
      tagError: Boolean = true,
      logError: Boolean = true
    ): ZIO[R with OpenTracing, E, A] =
      OpenTracing.span(zio, operation, tagError, logError)

    def spanFrom[R1 <: R with OpenTracing, C <: Object](
      format: Format[C],
      carrier: C,
      operation: String,
      tagError: Boolean = true,
      logError: Boolean = true
    ): ZIO[R1, E, A] =
      OpenTracing.spanFrom(format, carrier, zio, operation, tagError, logError)

    def setBaggageItem(key: String, value: String): ZIO[R with OpenTracing, E, A] =
      OpenTracing.setBaggageItem(zio, key, value)

    def tag(key: String, value: String): ZIO[R with OpenTracing, E, A] =
      OpenTracing.tag(zio, key, value)

    def tag(key: String, value: Int): ZIO[R with OpenTracing, E, A] =
      OpenTracing.tag(zio, key, value)

    def tag(key: String, value: Boolean): ZIO[R with OpenTracing, E, A] =
      OpenTracing.tag(zio, key, value)

    def log(msg: String): ZIO[R with OpenTracing, E, A] =
      OpenTracing.log(zio, msg)

    def log(fields: Map[String, _]): ZIO[R with OpenTracing, E, A] =
      OpenTracing.log(zio, fields)

  }
}
