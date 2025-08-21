package zio.telemetry.opentelemetry.aws.xray.propagator

import io.opentelemetry.context.propagation.TextMapPropagator
import io.opentelemetry.contrib.awsxray.propagator.{AwsXrayLambdaPropagator, AwsXrayPropagator}
import zio.telemetry.opentelemetry.context

/**
 * AWS X-Ray Trace Header propagation protocol.
 *
 * @see
 *   [[https://github.com/open-telemetry/opentelemetry-java-contrib/blob/main/aws-xray-propagator/README.md]]
 */
object ContextPropagator {

  /**
   * AWS X-Ray context propagator.
   *
   * To combine with the default OTEL propagators:
   *
   * {{{
   *   ContextPropagator.combine(
   *     ContextPropagator.default,
   *     zio.telemetry.opentelemetry.aws.xray.propagator.ContextPropagator.awsXray
   *   )
   * }}}
   *
   * @see
   *   [[https://docs.aws.amazon.com/xray/latest/devguide/xray-concepts.html#xray-concepts-tracingheader]]
   */
  val awsXray: context.ContextPropagator =
    new context.ContextPropagator {
      override val instance: TextMapPropagator =
        AwsXrayPropagator.getInstance()
    }

  /**
   * AWS X-Ray context propagator for Lambda functions.
   *
   * To combine with the default OTEL propagators:
   *
   * {{{
   *   ContextPropagator.combine(
   *     ContextPropagator.default,
   *     zio.telemetry.opentelemetry.aws.xray.propagator.ContextPropagator.awsXrayLambda
   *   )
   * }}}
   */
  val awsXrayLambda: context.ContextPropagator =
    new context.ContextPropagator {
      override val instance: TextMapPropagator =
        AwsXrayLambdaPropagator.getInstance()
    }

}
