enablePlugins(ZioSbtEcosystemPlugin, ZioSbtCiPlugin)

inThisBuild(
  List(
    name               := "ZIO Telemetry",
    organization       := "dev.zio",
    zioVersion         := "2.0.13",
    homepage           := Some(url("https://zio.dev/zio-telemetry/")),
    licenses           := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    developers         := List(
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
    crossScalaVersions := Seq(scala212.value, scala213.value, scala3.value),
    ciEnabledBranches  := Seq("series/2.x"),
    pgpPassphrase      := sys.env.get("PGP_PASSWORD").map(_.toArray),
    pgpPublicRing      := file("/tmp/public.asc"),
    pgpSecretRing      := file("/tmp/secret.asc"),
    scmInfo            := Some(
      ScmInfo(
        url("https://github.com/zio/zio-telemetry/"),
        "scm:git:git@github.com:zio/zio-telemetry.git"
      )
    )
  )
)

Global / onChangedBuildSource := ReloadOnSourceChanges

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("check", "ciCheck;docsCheck")
addCommandAlias("ciCheck", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")
addCommandAlias("docsCheck", "docs/checkReadme;docs/ciCheckGithubWorkflow")
addCommandAlias(
  "compileExamples",
  "opentracingExample/compile;opentelemetryExample/compile;opentelemetryInstrumentationExample/compile"
)

// Fix 'Flag set repeatedly' error allegedly introduced by the usage of sdtSettings
lazy val tempFixScalacOptions =
  Seq("-deprecation", "-encoding", "utf8", "-feature", "-unchecked", "-language:implicitConversions")

lazy val root =
  project
    .in(file("."))
    .settings(publish / skip := true)
    .aggregate(opentracing, opentelemetry, opencensus, docs)

lazy val opentracing =
  project
    .in(file("opentracing"))
    .settings(enableZIO())
    .settings(
      stdSettings(
        name = Some("zio-opentracing"),
        packageName = Some("zio.telemetry.opentracing")
      ),
      scalacOptions --= tempFixScalacOptions
    )
    .settings(libraryDependencies ++= Dependencies.opentracing)

lazy val opentelemetry =
  project
    .in(file("opentelemetry"))
    .settings(enableZIO())
    .settings(
      stdSettings(
        name = Some("zio-opentelemetry"),
        packageName = Some("zio.telemetry.opentelemetry")
      ),
      scalacOptions --= tempFixScalacOptions
    )
    .settings(libraryDependencies ++= Dependencies.opentelemetry)

lazy val opencensus = project
  .in(file("opencensus"))
  .settings(enableZIO())
  .settings(
    stdSettings(
      name = Some("zio-opencensus"),
      packageName = Some("zio.telemetry.opencensus")
    ),
    scalacOptions --= tempFixScalacOptions
  )
  .settings(libraryDependencies ++= Dependencies.opencensus)

lazy val opentracingExample =
  project
    .in(file("opentracing-example"))
    .settings(enableZIO())
    .settings(
      crossScalaVersions := Seq(scala212.value, scala213.value),
      stdSettings(
        name = Some("opentracing-example"),
        packageName = Some("zio.telemetry.opentracing.example")
      )
    )
    .settings(publish / skip := true)
    .settings(libraryDependencies ++= Dependencies.opentracingExample)
    .dependsOn(opentracing)

lazy val opentelemetryExample =
  project
    .in(file("opentelemetry-example"))
    .settings(enableZIO())
    .settings(
      crossScalaVersions := Seq(scala212.value, scala213.value),
      stdSettings(
        name = Some("opentelemetry-example"),
        packageName = Some("zio.telemetry.opentelemetry.example")
      )
    )
    .settings(publish / skip := true)
    .settings(libraryDependencies ++= Dependencies.opentelemetryExample)
    .dependsOn(opentelemetry)

lazy val opentelemetryInstrumentationExample =
  project
    .in(file("opentelemetry-instrumentation-example"))
    .settings(enableZIO())
    .settings(
      crossScalaVersions := Seq(scala212.value, scala213.value),
      stdSettings(
        name = Some("opentelemetry-instrumentation-example"),
        packageName = Some("zio.telemetry.opentelemetry.instrumentation.example")
      )
    )
    .settings(publish / skip := true)
    .settings(libraryDependencies ++= Dependencies.opentelemetryInstrumentationExample)
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
      ScalaUnidoc / unidoc / unidocProjectFilter := inProjects(opentracing, opentelemetry, opencensus)
    )
    .dependsOn(opentracing, opentelemetry, opencensus)
    .enablePlugins(WebsitePlugin)
