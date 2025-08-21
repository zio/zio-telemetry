package zio.telemetry.opentelemetry.metrics

import io.opentelemetry.api
import zio._
import zio.metrics.MetricLabel
import zio.telemetry.opentelemetry.common.{Attribute, Attributes}

package object internal {

  private[metrics] def attributes(tags: Set[MetricLabel]): api.common.Attributes =
    Attributes.fromList(tags.map(t => Attribute.string(t.key, t.value)).toList)

  private[metrics] def logAnnotatedAttributes(attributes: api.common.Attributes, logAnnotated: Boolean)(implicit
    trace: Trace
  ): UIO[api.common.Attributes] =
    if (logAnnotated)
      for {
        annotations <- ZIO.logAnnotations
        annotated    = Attributes.fromList(annotations.map { case (k, v) => Attribute.string(k, v) }.toList)
        builder      = api.common.Attributes.builder()
        _            = builder.putAll(annotated)
        _            = builder.putAll(attributes)
      } yield builder.build()
    else
      ZIO.succeed(attributes)

}
