inThisBuild(
  List(
    organization := "dev.zio",
    licenses := List(
      "Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")
    ),
    developers := List(
      Developer(
        "mijicd",
        "Dejan Mijic",
        "dmijic@acm.org",
        url("https://github.com/mijicd")
      ),
      Developer(
        "runtologist",
        "Simon Schenk",
        "simon@schenk-online.net",
        url("https://github.com/runtologist")
      )
    ),
    pgpPublicRing := file("/tmp/public.asc"),
    pgpSecretRing := file("/tmp/secret.asc"),
    releaseEarlyWith := SonatypePublisher,
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/zio/zio-telemetry/"),
        "scm:git:git@github.com:zio/zio-telemetry.git"
      )
    )
  )
)

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias(
  "check",
  "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck"
)

val zioVersion = "1.0.0-RC16"
val opentracingVersion = "0.33.0"

lazy val `zio-opentracing` =
  (project in file("."))
    .settings(
      libraryDependencies := Seq(
        "dev.zio" %% "zio" % zioVersion,
        "dev.zio" %% "zio-test" % zioVersion % Test,
        "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
        "io.opentracing" % "opentracing-api" % opentracingVersion,
        "io.opentracing" % "opentracing-mock" % opentracingVersion % Test,
        "org.scala-lang.modules" %% "scala-collection-compat" % "2.1.1"
      )
    )
addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")

testFrameworks := Seq(new TestFramework("zio.test.sbt.ZTestFramework"))
