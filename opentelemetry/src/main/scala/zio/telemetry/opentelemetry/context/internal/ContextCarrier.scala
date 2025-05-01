package zio.telemetry.opentelemetry.context.internal

private[opentelemetry] trait ContextCarrier[T] {

  /**
   * The data to be injected/extracted into/from the current context
   */
  val kernel: T

}
