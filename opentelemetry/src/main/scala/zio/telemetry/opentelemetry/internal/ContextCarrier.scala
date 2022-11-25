package zio.telemetry.opentelemetry.internal

private[opentelemetry] trait ContextCarrier[T] {

  val kernel: T

}
