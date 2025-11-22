package zio.telemetry.opentelemetry.common

import zio.test.{ZIOSpecDefault, _}
import zio.telemetry.opentelemetry.core.common.{Attribute, Attributes}

object AttributesSpec extends ZIOSpecDefault {

  override def spec: Spec[Any, Throwable] =
    suite("zio opentelemetry")(
      suite("Attributes")(
        // Addresses the bug: https://github.com/zio/zio-telemetry/issues/911
        test("Check methods resolution in compile time") {
          val _ = Attributes(Attribute.string("foo", "bar"), Attribute.string("dog", "fox"))

          assertTrue(true);
        }
      )
    )

}
