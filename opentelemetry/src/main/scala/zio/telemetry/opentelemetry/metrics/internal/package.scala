package zio.telemetry.opentelemetry.metrics

import zio.metrics.MetricLabel
import zio.telemetry.opentelemetry.common.Attribute
import io.opentelemetry.api
import zio.telemetry.opentelemetry.common.Attributes

package object internal {

  def attributes(tags: Set[MetricLabel]): api.common.Attributes =
    Attributes(tags.map(t => Attribute.string(t.key, t.value)).toSeq: _*)

}
