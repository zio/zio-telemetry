package zio.telemetry.opentelemetry.testkit.metrics

import zio.telemetry.opentelemetry.testkit.common.Attributes
import io.opentelemetry.sdk.metrics.data.{MetricDataType => JMetricDataType}
import io.opentelemetry.sdk.metrics.data.{MetricData => JMetricData}
import io.opentelemetry.sdk.metrics.data.DoubleExemplarData
import io.opentelemetry.sdk.metrics.data.LongExemplarData
import io.opentelemetry.sdk.metrics.data.DoublePointData
import scala.jdk.CollectionConverters._
import io.opentelemetry.sdk.metrics.data.LongPointData
import io.opentelemetry.sdk.metrics.data.HistogramPointData
import zio.telemetry.opentelemetry.testkit.metrics.MetricData.PointData
import zio.telemetry.opentelemetry.testkit.common.SpanContext
import zio.telemetry.opentelemetry.testkit.common.InstrumentationScopeInfo

trait MetricData[T <: PointData] {
  val instrumentationScopeInfo: InstrumentationScopeInfo
  val name: String
  val description: String
  val unit: String
  val `type`: JMetricDataType
  val points: List[T]
}

object MetricData {

  final case class Counter(
    instrumentationScopeInfo: InstrumentationScopeInfo,
    name: String,
    description: String,
    unit: String,
    `type`: JMetricDataType,
    points: List[PointData.Long]
  ) extends MetricData[PointData.Long]

  object Counter {
    def apply(underlying: JMetricData): Counter =
      Counter(
        instrumentationScopeInfo = InstrumentationScopeInfo(underlying.getInstrumentationScopeInfo),
        name = underlying.getName,
        description = underlying.getDescription,
        unit = underlying.getUnit,
        `type` = underlying.getType,
        points = underlying.getLongSumData.getPoints.asScala.toList.map(PointData.Long(_))
      )
  }

  final case class Gauge(
    instrumentationScopeInfo: InstrumentationScopeInfo,
    name: String,
    description: String,
    unit: String,
    `type`: JMetricDataType,
    points: List[PointData.Double]
  ) extends MetricData[PointData.Double]

  object Gauge {
    def apply(underlying: JMetricData): Gauge =
      Gauge(
        instrumentationScopeInfo = InstrumentationScopeInfo(underlying.getInstrumentationScopeInfo),
        name = underlying.getName,
        description = underlying.getDescription,
        unit = underlying.getUnit,
        `type` = underlying.getType,
        points = underlying.getDoubleGaugeData.getPoints.asScala.toList.map(PointData.Double(_))
      )
  }

  final case class Histogram(
    instrumentationScopeInfo: InstrumentationScopeInfo,
    name: String,
    description: String,
    unit: String,
    `type`: JMetricDataType,
    points: List[PointData.Histogram]
  ) extends MetricData[PointData.Histogram]

  object Histogram {
    def apply(underlying: JMetricData): Histogram =
      Histogram(
        instrumentationScopeInfo = InstrumentationScopeInfo(underlying.getInstrumentationScopeInfo),
        name = underlying.getName,
        description = underlying.getDescription,
        unit = underlying.getUnit,
        `type` = underlying.getType,
        points = underlying.getHistogramData.getPoints.asScala.toList.map(PointData.Histogram(_))
      )
  }

  trait PointData {
    val startEpochNanos: Long
    val epochNanos: Long
    val attributes: Attributes
    val exemplars: List[_ <: ExamplarData]
  }

  object PointData {

    final case class Double(
      value: scala.Double,
      startEpochNanos: scala.Long,
      epochNanos: scala.Long,
      attributes: Attributes,
      exemplars: List[ExamplarData.Double]
    ) extends PointData

    object Double {
      def apply(underlying: DoublePointData): Double =
        Double(
          value = underlying.getValue,
          startEpochNanos = underlying.getStartEpochNanos,
          epochNanos = underlying.getEpochNanos,
          attributes = Attributes(underlying.getAttributes),
          exemplars = underlying.getExemplars.asScala.toList.map(ExamplarData.Double(_))
        )
    }

    final case class Long(
      value: scala.Long,
      startEpochNanos: scala.Long,
      epochNanos: scala.Long,
      attributes: Attributes,
      exemplars: List[ExamplarData.Long]
    ) extends PointData

    object Long {
      def apply(underlying: LongPointData): Long =
        Long(
          value = underlying.getValue,
          startEpochNanos = underlying.getStartEpochNanos,
          epochNanos = underlying.getEpochNanos,
          attributes = Attributes(underlying.getAttributes),
          exemplars = underlying.getExemplars.asScala.toList.map(ExamplarData.Long(_))
        )
    }

    final case class Histogram(
      sum: scala.Double,
      count: scala.Long,
      min: scala.Double,
      max: scala.Double,
      boundaries: List[scala.Double],
      counts: List[scala.Long],
      startEpochNanos: scala.Long,
      epochNanos: scala.Long,
      attributes: Attributes,
      exemplars: List[ExamplarData.Double]
    ) extends PointData

    object Histogram {
      def apply(underlying: HistogramPointData): Histogram =
        Histogram(
          sum = underlying.getSum,
          count = underlying.getCount,
          min = underlying.getMin,
          max = underlying.getMax,
          boundaries = underlying.getBoundaries.asScala.toList.map(scala.Double.unbox(_)),
          counts = underlying.getCounts.asScala.toList.map(scala.Long.unbox(_)),
          startEpochNanos = underlying.getStartEpochNanos,
          epochNanos = underlying.getEpochNanos,
          attributes = Attributes(underlying.getAttributes),
          exemplars = underlying.getExemplars.asScala.toList.map(ExamplarData.Double(_))
        )
    }

  }

  trait ExamplarData {
    val filteredAttributes: Attributes
    val epochNanos: Long
    val spanContext: SpanContext
  }

  object ExamplarData {

    final case class Double(
      value: scala.Double,
      filteredAttributes: Attributes,
      epochNanos: scala.Long,
      spanContext: SpanContext
    ) extends ExamplarData

    object Double {
      def apply(underlying: DoubleExemplarData): Double =
        Double(
          value = underlying.getValue,
          filteredAttributes = Attributes(underlying.getFilteredAttributes),
          epochNanos = underlying.getEpochNanos,
          spanContext = SpanContext(underlying.getSpanContext)
        )
    }

    final case class Long(
      value: scala.Long,
      filteredAttributes: Attributes,
      epochNanos: scala.Long,
      spanContext: SpanContext
    ) extends ExamplarData

    object Long {
      def apply(underlying: LongExemplarData): Long =
        Long(
          value = underlying.getValue,
          filteredAttributes = Attributes(underlying.getFilteredAttributes),
          epochNanos = underlying.getEpochNanos,
          spanContext = SpanContext(underlying.getSpanContext)
        )
    }

  }

  

}
