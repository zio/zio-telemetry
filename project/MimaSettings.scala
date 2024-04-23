import com.typesafe.tools.mima.core.Problem
import com.typesafe.tools.mima.core.ProblemFilters.exclude
import com.typesafe.tools.mima.plugin.MimaKeys.*
import sbt.*
import sbt.Keys.{name, organization}

object MimaSettings {
  lazy val bincompatVersionToCompare = "3.0.0-RC1"

  def mimaSettings(failOnProblem: Boolean) =
    Seq(
      mimaPreviousArtifacts := Set(organization.value %% name.value % bincompatVersionToCompare),
      mimaBinaryIssueFilters ++= Seq(
        exclude[Problem]("zio.telemetry.opentelemetry.*")
      ),
      mimaFailOnProblem     := failOnProblem
    )
}
