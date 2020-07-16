import BuildHelper._

inThisBuild(
  List(
    organization := "dev.zio",
    homepage := Some(url("https://github.com/zio/zio-telemetry/")),
    licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
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
    pgpPassphrase := sys.env.get("PGP_PASSWORD").map(_.toArray),
    pgpPublicRing := file("/tmp/public.asc"),
    pgpSecretRing := file("/tmp/secret.asc"),
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/zio/zio-telemetry/"),
        "scm:git:git@github.com:zio/zio-telemetry.git"
      )
    )
  )
)

Global / onChangedBuildSource := ReloadOnSourceChanges
Global / testFrameworks := Seq(new TestFramework("zio.test.sbt.ZTestFramework"))

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("check", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")

lazy val root =
  project
    .in(file("."))
    .settings(skip in publish := true)
    .aggregate(opentracing, opentelemetry, opentracingExample, opentelemetryExample)

lazy val opentracing =
  project
    .in(file("opentracing"))
    .settings(stdSettings("zio-opentracing"))
    .settings(libraryDependencies := Dependencies.opentracing)

lazy val opentelemetry =
  project
    .in(file("opentelemetry"))
    .settings(stdSettings("zio-opentelemetry"))
    .settings(libraryDependencies := Dependencies.opentelemetry)

lazy val opencensus = project
  .in(file("opencensus"))
  .settings(stdSettings("zio-opencensus"))
  .settings(libraryDependencies := Dependencies.opencensus)

lazy val opentracingExample =
  project
    .in(file("opentracing-example"))
    .settings(stdSettings("opentracing-example"))
    .settings(skip in publish := true)
    .settings(libraryDependencies := Dependencies.opentracingExample)
    .settings(addCompilerPlugin("org.typelevel" % "kind-projector" % "0.11.0" cross CrossVersion.full))
    .dependsOn(opentracing)

lazy val opentelemetryExample =
  project
    .in(file("opentelemetry-example"))
    .settings(stdSettings("opentelemetry-example"))
    .settings(skip in publish := true)
    .settings(libraryDependencies := Dependencies.opentelemetryExample)
    .dependsOn(opentelemetry)
