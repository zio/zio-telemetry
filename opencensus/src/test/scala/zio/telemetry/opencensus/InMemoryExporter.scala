package zio.telemetry.opencensus

import io.opencensus.trace.`export`.SpanData
import io.opencensus.trace.`export`.SpanExporter.Handler
import io.opencensus.trace.{Tracing => OTracing}
import zio.{Ref, Runtime, Unsafe, ZIO}

import java.util
import scala.jdk.CollectionConverters._

object InMemoryExporter {

  private val runtime = Runtime.default

  def register(): ZIO[Any, Nothing, Ref[List[SpanData]]] =
    Ref
      .make(List.empty[SpanData])
      .map { x =>
        OTracing.getExportComponent.getSpanExporter.registerHandler("InMemoryExporter", new InMemoryExporter(x))
        x
      }

  class InMemoryExporter(finishedSpans: Ref[List[SpanData]]) extends Handler {
    override def `export`(spanDataList: util.Collection[SpanData]): Unit =
      Unsafe.unsafe { implicit unsafe =>
        runtime.unsafe.run(finishedSpans.update(x => x ++ spanDataList.asScala.toList)): Unit
        ()
      }
  }
}
