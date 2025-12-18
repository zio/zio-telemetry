package zio.telemetry.opentelemetry.core.common

import zio.test.{ZIOSpecDefault, _}

object AttributesSpec extends ZIOSpecDefault {

  override def spec: Spec[Any, Throwable] =
    suite("zio opentelemetry")(
      suite("Attributes")(
        // Addresses the bug: https://github.com/zio/zio-telemetry/issues/911
        test("Check methods resolution in compile time") {
          val _ = Attributes(Attribute.string("foo", "bar"), Attribute.string("dog", "fox"))

          assertTrue(true);
        },
        test("fromList accepts attributes of varying types") {
          val _ = Attributes.fromList(List(Attribute.string("foo", "bar"), Attribute.long("dog", 1)))

          assertTrue(true)
        }
      )
    )

}
