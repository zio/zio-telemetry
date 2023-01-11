import BuildHelper._

inThisBuild(
  List(
    organization  := "dev.zio",
    homepage      := Some(url("https://zio.dev/zio-telemetry/")),
    licenses      := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    developers    := List(
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
    scmInfo       := Some(
      ScmInfo(
        url("https://github.com/zio/zio-telemetry/"),
        "scm:git:git@github.com:zio/zio-telemetry.git"
      )
    )
  )
)

Global / onChangedBuildSource := ReloadOnSourceChanges
Global / testFrameworks       := Seq(new TestFramework("zio.test.sbt.ZTestFramework"))

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("check", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")
addCommandAlias("compileExamples", "opentracingExample/compile;opentelemetryExample/compile")

lazy val root =
  project
    .in(file("."))
    .settings(publish / skip := true)
    .aggregate(opentracing, opentelemetry, opencensus, docs)

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
    .settings(publish / skip := true)
    .settings(onlyWithScala2)
    .settings(libraryDependencies := Dependencies.opentracingExample)
    .dependsOn(opentracing)

lazy val opentelemetryExample =
  project
    .in(file("opentelemetry-example"))
    .settings(stdSettings("opentelemetry-example"))
    .settings(publish / skip := true)
    .settings(onlyWithScala2)
    .settings(libraryDependencies := Dependencies.opentelemetryExample)
    .dependsOn(opentelemetry)

lazy val docs =
  project
    .in(file("zio-telemetry-docs"))
    .settings(
      moduleName                                 := "zio-telemetry-docs",
      scalacOptions -= "-Yno-imports",
      scalacOptions -= "-Xfatal-warnings",
      projectName                                := "ZIO Telemetry",
      mainModuleName                             := (opentracing / moduleName).value,
      projectStage                               := ProjectStage.ProductionReady,
      ScalaUnidoc / unidoc / unidocProjectFilter := inProjects(opentracing, opentelemetry, opencensus),
      docsPublishBranch                          := "series/2.x"
    )
    .dependsOn(opentracing, opentelemetry, opencensus)
    .enablePlugins(WebsitePlugin)
