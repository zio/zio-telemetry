import MimaSettings.mimaSettings
import ch.epfl.scala.sbtmissinglink.MissingLinkPlugin.missinglinkConflictsTag
import zio.sbt.githubactions.Step.SingleStep
import zio.sbt.githubactions.ActionRef
import zio.json.ast.Json

enablePlugins(ZioSbtEcosystemPlugin, ZioSbtCiPlugin)

inThisBuild(
  List(
    name              := "ZIO Telemetry",
    organization      := "dev.zio",
    zioVersion        := "2.1.25",
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
    ciEnabledBranches := Seq("series/2.x", "v4.0.0-rc"),
    ciCheckArtifactsBuildSteps ++= Seq(
      SingleStep(
        name = "Compile examples",
        run = Some("sbt compileExamples")
      ),
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
    scala213          := "2.13.18"
  )
)

Global / onChangedBuildSource := ReloadOnSourceChanges

// Docusaurus 2.x uses webpackbar@5.0.2 which is incompatible with webpack@5.75+
// (npm now resolves webpack@^5.73.0 to the latest 5.x which breaks ProgressPlugin).
// Workaround: run installWebsite + mdoc separately, then pin webpack to 5.74.0 via
// npm overrides before running the final npm build.
ThisBuild / ciCheckWebsiteBuildProcess := Seq(
  SingleStep(
    name = "Setup NodeJs",
    uses = Some(ActionRef("actions/setup-node@v6")),
    parameters = Map("node-version" -> Json.Str("20"))
  ),
  SingleStep(
    name = "Check website build process",
    run = Some(
      """|sbt docs/clean "docs/installWebsite" "docs/mdoc"
         |node -e "const fs=require('fs'),p='zio-telemetry-docs/target/website/package.json',pkg=JSON.parse(fs.readFileSync(p,'utf8'));pkg.overrides={webpack:'5.74.0'};fs.writeFileSync(p,JSON.stringify(pkg,null,2));"
         |npm install --prefix zio-telemetry-docs/target/website
         |npm --prefix zio-telemetry-docs/target/website run build""".stripMargin
    )
  )
)

addCommandAlias("check", "ciCheck;docsCheck")
addCommandAlias("ciCheck", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")
addCommandAlias("docsCheck", "docs/checkReadme;docs/ciCheckGithubWorkflow")
addCommandAlias(
  "compileExamples",
  "opentracingManualExample/compile;opentelemetryManualExample/compile;opentelemetryAutoinstrumentationExample/compile"
)
addCommandAlias(
  "mimaChecks",
  "all opentracing/mimaReportBinaryIssues opentelemetry/mimaReportBinaryIssues opencensus/mimaReportBinaryIssues"
)
addCommandAlias(
  "fmtExamples",
  List(
    "opentracingManualExample/scalafmtAll;opentracingManualExample/scalafixAll",
    "opentelemetryManualExample/scalafmtAll;opentelemetryManualExample/scalafixAll",
    "opentelemetryAutoinstrumentationExample/scalafmtAll;opentelemetryAutoinstrumentationExample/scalafixAll"
  ).mkString(";")
)

def stdModuleSettings(name: Option[String], packageName: Option[String]) =
  stdSettings(name, packageName) ++
    Seq(
      crossScalaVersions := Seq(scala213.value, scala212.value, scala3.value),
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
    .aggregate(
      opentelemetry,
      opentelemetryCore,
      opentelemetryTestkit,
      opentelemetryZioLogging,
      opentelemetryAwsXrayPropagator,
      opentelemetryExtensionTracePropagators,
      opentracing,
      opencensus,
      docs
    )

lazy val opentelemetry: Project =
  project
    .in(file("modules/opentelemetry/main"))
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
    .dependsOn(opentelemetryCore, opentelemetryTestkit % Test)

lazy val opentelemetryCore =
  project
    .in(file("modules/opentelemetry/core"))
    .settings(enableZIO())
    .settings(
      stdModuleSettings(
        name = Some("zio-opentelemetry-core"),
        packageName = Some("zio.telemetry.opentelemetry.core")
      )
    )
    .settings(libraryDependencies ++= Dependencies.opentelemetryCore)
    .settings(mimaSettings(failOnProblem = true))
    .settings(unusedCompileDependenciesFilter -= moduleFilter("org.scala-lang.modules", "scala-collection-compat"))

lazy val opentelemetryTestkit =
  project
    .in(file("modules/opentelemetry/testkit"))
    .settings(enableZIO())
    .settings(
      stdModuleSettings(
        name = Some("zio-opentelemetry-testkit"),
        packageName = Some("zio.telemetry.opentelemetry.testkit")
      )
    )
    .settings(libraryDependencies ++= Dependencies.opentelemetryTestkit)
    .settings(mimaSettings(failOnProblem = true))
    .settings(unusedCompileDependenciesFilter -= moduleFilter("io.opentelemetry", "opentelemetry-api-incubator"))
    .dependsOn(opentelemetryCore)

lazy val opentelemetryZioLogging =
  project
    .in(file("modules/opentelemetry/zio-logging"))
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
    .dependsOn(opentelemetryCore, opentelemetryTestkit % Test)

lazy val opentelemetryAwsXrayPropagator =
  project
    .in(file("modules/opentelemetry/aws-xray-propagator"))
    .settings(
      stdModuleSettings(
        name = Some("zio-opentelemetry-aws-xray-propagator"),
        packageName = Some("zio.telemetry.opentelemetry.aws.xray.propagator")
      )
    )
    .settings(libraryDependencies ++= Dependencies.opentelemetryAwsXrayPropagator)
    .settings(mimaSettings(failOnProblem = true))
    .dependsOn(opentelemetryCore)

lazy val opentelemetryExtensionTracePropagators =
  project
    .in(file("modules/opentelemetry/extension-trace-propagators"))
    .settings(
      stdModuleSettings(
        name = Some("zio-opentelemetry-extension-trace-propagators"),
        packageName = Some("zio.telemetry.opentelemetry.extension.trace.propagation")
      )
    )
    .settings(libraryDependencies ++= Dependencies.opentelemetryExtensionTracePropagators)
    .settings(mimaSettings(failOnProblem = true))
    .dependsOn(opentelemetryCore)

lazy val opentracing =
  project
    .in(file("modules/opentracing/main"))
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

lazy val opencensus =
  project
    .in(file("modules/opencensus/main"))
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

lazy val opentracingManualExample =
  project
    .in(file("modules/examples/opentracing/manual"))
    .settings(enableZIO())
    .settings(
      stdExampleSettings(
        name = Some("opentracing-manual-example"),
        packageName = Some("zio.telemetry.opentracing.example")
      )
    )
    .settings(libraryDependencies ++= Dependencies.opentracingManualExample)
    .dependsOn(opentracing)

lazy val opentelemetryManualExample =
  project
    .in(file("modules/examples/opentelemetry/manual"))
    .settings(enableZIO())
    .settings(
      stdExampleSettings(
        name = Some("opentelemetry-manual-example"),
        packageName = Some("zio.telemetry.opentelemetry.example")
      )
    )
    .settings(libraryDependencies ++= Dependencies.opentelemetryManualExample)
    .dependsOn(opentelemetry)

lazy val opentelemetryAutoinstrumentationExample =
  project
    .in(file("modules/examples/opentelemetry/autoinstrumentation"))
    .settings(enableZIO())
    .settings(
      stdExampleSettings(
        name = Some("opentelemetry-autoinstrumentation-example"),
        packageName = Some("zio.telemetry.opentelemetry.instrumentation.example")
      )
    )
    .settings(libraryDependencies ++= Dependencies.opentelemetryAutoinstrumentationExample)
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
