addSbtPlugin("com.geirsson"                      % "sbt-ci-release"   % "1.5.7")
addSbtPlugin("com.thoughtworks.sbt-api-mappings" % "sbt-api-mappings" % "3.0.0")
addSbtPlugin("ch.epfl.scala"                     % "sbt-bloop"        % "1.4.8")
addSbtPlugin("io.github.davidgregory084"         % "sbt-tpolecat"     % "0.1.19")
addSbtPlugin("org.scalameta"                     % "sbt-scalafmt"     % "2.4.2")
addSbtPlugin("org.scoverage"                     % "sbt-scoverage"    % "1.8.2")

libraryDependencies += "org.snakeyaml" % "snakeyaml-engine" % "2.3"
