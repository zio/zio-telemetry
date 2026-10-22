import sbt._

object GithubActionsVersions {
  val overrides: Map[String, String] = Map(
    "scala-steward-org/scala-steward-action" -> "v2.97.0",
    "sbt/setup-sbt"                          -> "v1.5.12",
    "coursier/cache-action"                  -> "v8.2.0"
  )

  def update(directory: File): Unit =
    (directory ** "*.yml").get.foreach { file =>
      val original = IO.read(file)
      val updated  = overrides.foldLeft(original) { case (content, (action, version)) =>
        content.replaceAll(
          java.util.regex.Pattern.quote(action) + "@[A-Za-z0-9._-]+",
          java.util.regex.Matcher.quoteReplacement(action + "@" + version)
        )
      }
      if (updated != original) IO.write(file, updated)
    }
}
