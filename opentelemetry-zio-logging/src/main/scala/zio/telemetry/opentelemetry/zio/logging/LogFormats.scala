package zio.telemetry.opentelemetry.zio.logging

import zio.logging.LogFormat
import zio.logging.LogFormat.label

trait LogFormats {

  /**
   * Will print traceId from current span or nothing when not in span
   */
  def traceId: LogFormat

  /**
   * Will print spanId from current span or nothing when not in span
   */
  def spanId: LogFormat

  /**
   * Label with `traceId` key and [[traceId]] value
   */
  def traceIdLabel: LogFormat = label("traceId", traceId)

  /**
   * Label with `spanId` key and [[spanId]] value
   */
  def spanIdLabel: LogFormat = label("spanId", spanId)
}