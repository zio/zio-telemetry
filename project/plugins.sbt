addSbtPlugin("com.github.sbt"                    % "sbt-ci-release"   % "1.5.10")
addSbtPlugin("com.thoughtworks.sbt-api-mappings" % "sbt-api-mappings" % "3.0.0")
addSbtPlugin("com.github.sbt"                    % "sbt-unidoc"       % "0.5.0")
addSbtPlugin("ch.epfl.scala"                     % "sbt-bloop"        % "1.4.12")
addSbtPlugin("io.github.davidgregory084"         % "sbt-tpolecat"     % "0.1.20")
addSbtPlugin("org.scalameta"                     % "sbt-scalafmt"     % "2.4.6")
addSbtPlugin("org.scalameta"                     % "sbt-mdoc"         % "2.3.2")
addSbtPlugin("org.scoverage"                     % "sbt-scoverage"    % "1.9.3")

libraryDependencies += "org.snakeyaml" % "snakeyaml-engine" % "2.3"
