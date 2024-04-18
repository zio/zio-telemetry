package zio.telemetry.opentelemetry.metrics

import io.opentelemetry.api
import zio.metrics.MetricLabel
import zio.telemetry.opentelemetry.common.{Attribute, Attributes}

package object internal {

  private[metrics] def attributes(tags: Set[MetricLabel]): api.common.Attributes =
    Attributes(tags.map(t => Attribute.string(t.key, t.value)).toSeq: _*)

}
