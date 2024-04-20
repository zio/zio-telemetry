package zio.telemetry.opentelemetry.metrics

import io.opentelemetry.api
import zio._
import zio.metrics.MetricLabel
import zio.telemetry.opentelemetry.common.{Attribute, Attributes}

package object internal {

  private[metrics] def attributes(tags: Set[MetricLabel]): api.common.Attributes =
    Attributes(tags.map(t => Attribute.string(t.key, t.value)).toSeq: _*)

  private[metrics] def logAnnotatedAttributes(attributes: api.common.Attributes, logAnnotated: Boolean)(implicit
    trace: Trace
  ): UIO[api.common.Attributes] =
    if (logAnnotated)
      for {
        annotations <- ZIO.logAnnotations
        annotated    = Attributes(annotations.map { case (k, v) => Attribute.string(k, v) }.toSeq: _*)
        builder      = api.common.Attributes.builder()
        _            = builder.putAll(annotated)
        _            = builder.putAll(attributes)
      } yield builder.build()
    else
      ZIO.succeed(attributes)

}
