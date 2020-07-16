package zio.telemetry.opentracing.example

import io.opentracing.Tracer
import io.opentracing.propagation.{Format, TextMapAdapter}
import org.http4s.Headers
import zio.telemetry.opentracing.example.JavaConverters.collection._
import zio.telemetry.opentracing.{Context, ContextExtractor}

final case class HttpOperation(operationName: String, headers: Headers)

object HttpOperation {
  val contextExtractor: ContextExtractor[HttpOperation] = new ContextExtractor[HttpOperation] {
    def extract(tracer: Tracer, context: HttpOperation): Context = {
      val headers = context.headers.toList.map(x => x.name.value -> x.value).toMap
      Context(
        context.operationName,
        tracer.extract(Format.Builtin.TEXT_MAP, new TextMapAdapter(headers.asJava)))
    }
  }
}
