import MimaSettings.mimaSettings
import ch.epfl.scala.sbtmissinglink.MissingLinkPlugin.missinglinkConflictsTag
import zio.sbt.githubactions.Step.SingleStep

enablePlugins(ZioSbtEcosystemPlugin, ZioSbtCiPlugin)

inThisBuild(
  List(
    name              := "ZIO Telemetry",
    organization      := "dev.zio",
    zioVersion        := "2.1.12",
    homepage          := Some(url("https://zio.dev/zio-telemetry/")),
    licenses          := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    developers        := List(
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
      ),
      Developer(
        "grouzen",
        "Michael Nedokushev",
        "michael.nedokushev@gmail.com",
        url("https://github.com/grouzen")
      )
    ),
    ciEnabledBranches := Seq("series/2.x"),
    ciCheckArtifactsBuildSteps ++= Seq(
      SingleStep(
        name = "Mima check",
        run = Some("sbt mimaChecks")
      ),
      SingleStep(
        name = "Undeclared dependencies check",
        run = Some("sbt undeclaredCompileDependencies")
      ),
      SingleStep(
        name = "Unused dependencies check",
        run = Some("sbt unusedCompileDependenciesTest")
      ),
      SingleStep(
        name = "MissingLink",
        run = Some("sbt missinglinkCheck")
      )
    ),
    pgpPassphrase     := sys.env.get("PGP_PASSWORD").map(_.toArray),
    pgpPublicRing     := file("/tmp/public.asc"),
    pgpSecretRing     := file("/tmp/secret.asc"),
    scmInfo           := Some(
      ScmInfo(
        url("https://github.com/zio/zio-telemetry/"),
        "scm:git:git@github.com:zio/zio-telemetry.git"
      )
    ),
    concurrentRestrictions += Tags.limit(missinglinkConflictsTag, 1),
    // TODO: remove once it is updated in zio-sbt
    scala213          := "2.13.15"
  )
)

Global / onChangedBuildSource := ReloadOnSourceChanges

addCommandAlias("check", "ciCheck;docsCheck")
addCommandAlias("ciCheck", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")
addCommandAlias("docsCheck", "docs/checkReadme;docs/ciCheckGithubWorkflow")
addCommandAlias(
  "compileExamples",
  "opentracingExample/compile;opentelemetryExample/compile;opentelemetryInstrumentationExample/compile"
)
addCommandAlias(
  "mimaChecks",
  "all opentracing/mimaReportBinaryIssues opentelemetry/mimaReportBinaryIssues opencensus/mimaReportBinaryIssues"
)

def stdModuleSettings(name: Option[String], packageName: Option[String]) =
  stdSettings(name, packageName) ++
    Seq(
      crossScalaVersions := Seq(scala212.value, scala213.value, scala3.value),
      // Fix 'Flag set repeatedly' error allegedly introduced by the usage of sdtSettings: https://github.com/zio/zio-sbt/issues/221
      scalacOptions --= Seq(
        "-deprecation",
        "-encoding",
        "utf8",
        "-feature",
        "-unchecked",
        "-language:implicitConversions"
      )
    )

def stdExampleSettings(name: Option[String], packageName: Option[String]) =
  stdSettings(name, packageName) ++
    Seq(
      crossScalaVersions := Seq(scala212.value, scala213.value),
      publish / skip     := true
    )

lazy val root =
  project
    .in(file("."))
    .settings(publish / skip := true)
    .aggregate(opentracing, opentelemetry, opencensus, opentelemetryZioLogging, docs)

lazy val opentracing =
  project
    .in(file("opentracing"))
    .settings(enableZIO())
    .settings(
      stdModuleSettings(
        name = Some("zio-opentracing"),
        packageName = Some("zio.telemetry.opentracing")
      )
    )
    .settings(libraryDependencies ++= Dependencies.opentracing)
    .settings(mimaSettings(failOnProblem = true))
    .settings(unusedCompileDependenciesFilter -= moduleFilter("org.scala-lang.modules", "scala-collection-compat"))

lazy val opentelemetry =
  project
    .in(file("opentelemetry"))
    .settings(enableZIO())
    .settings(
      stdModuleSettings(
        name = Some("zio-opentelemetry"),
        packageName = Some("zio.telemetry.opentelemetry")
      )
    )
    .settings(libraryDependencies ++= Dependencies.opentelemetry)
    .settings(mimaSettings(failOnProblem = true))
    .settings(unusedCompileDependenciesFilter -= moduleFilter("org.scala-lang.modules", "scala-collection-compat"))

lazy val opencensus = project
  .in(file("opencensus"))
  .settings(enableZIO())
  .settings(
    stdModuleSettings(
      name = Some("zio-opencensus"),
      packageName = Some("zio.telemetry.opencensus")
    )
  )
  .settings(libraryDependencies ++= Dependencies.opencensus)
  .settings(mimaSettings(failOnProblem = true))
  .settings(unusedCompileDependenciesFilter -= moduleFilter("io.opencensus", "opencensus-impl"))

lazy val opentelemetryZioLogging = project
  .in(file("opentelemetry-zio-logging"))
  .settings(enableZIO())
  .settings(
    stdModuleSettings(
      name = Some("zio-opentelemetry-zio-logging"),
      packageName = Some("zio.telemetry.opentelemetry.zio.logging")
    )
  )
  .settings(libraryDependencies ++= Dependencies.opentelemetryZioLogging)
  .settings(mimaSettings(failOnProblem = true))
  .settings(missinglinkIgnoreDestinationPackages += IgnoredPackage("scala.reflect"))
  .dependsOn(opentelemetry)

lazy val opentracingExample =
  project
    .in(file("opentracing-example"))
    .settings(enableZIO())
    .settings(
      stdExampleSettings(
        name = Some("opentracing-example"),
        packageName = Some("zio.telemetry.opentracing.example")
      )
    )
    .settings(libraryDependencies ++= Dependencies.opentracingExample)
    .dependsOn(opentracing)

lazy val opentelemetryExample =
  project
    .in(file("opentelemetry-example"))
    .settings(enableZIO())
    .settings(
      stdExampleSettings(
        name = Some("opentelemetry-example"),
        packageName = Some("zio.telemetry.opentelemetry.example")
      )
    )
    .settings(libraryDependencies ++= Dependencies.opentelemetryExample)
    .dependsOn(opentelemetry)

lazy val opentelemetryInstrumentationExample =
  project
    .in(file("opentelemetry-instrumentation-example"))
    .settings(enableZIO())
    .settings(
      stdExampleSettings(
        name = Some("opentelemetry-instrumentation-example"),
        packageName = Some("zio.telemetry.opentelemetry.instrumentation.example")
      )
    )
    .settings(libraryDependencies ++= Dependencies.opentelemetryInstrumentationExample)
    .dependsOn(opentelemetry)

lazy val docs =
  project
    .in(file("zio-telemetry-docs"))
    .settings(
      crossScalaVersions                         := Seq(scala212.value, scala213.value, scala3.value),
      moduleName                                 := "zio-telemetry-docs",
      projectName                                := "ZIO Telemetry",
      mainModuleName                             := (opentracing / moduleName).value,
      projectStage                               := ProjectStage.ProductionReady,
      ScalaUnidoc / unidoc / unidocProjectFilter := inProjects(
        opentracing,
        opentelemetry,
        opencensus
        //  opentelemetryZioLogging TODO: Causes some weird import issues
      ),
      scalacOptions --= Seq("-Yno-imports", "-Xfatal-warnings")
    )
    .settings(unusedCompileDependenciesFilter -= moduleFilter("org.scalameta", "mdoc"))
    .dependsOn(opentracing, opentelemetry, opencensus, opentelemetryZioLogging)
    .enablePlugins(WebsitePlugin)
