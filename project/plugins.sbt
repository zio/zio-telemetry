addSbtPlugin("com.geirsson"                      % "sbt-ci-release"   % "1.5.7")
addSbtPlugin("com.thoughtworks.sbt-api-mappings" % "sbt-api-mappings" % "3.0.0")
addSbtPlugin("com.eed3si9n"                      % "sbt-unidoc"       % "0.4.3")
addSbtPlugin("ch.epfl.scala"                     % "sbt-bloop"        % "1.4.9")
addSbtPlugin("io.github.davidgregory084"         % "sbt-tpolecat"     % "0.1.20")
addSbtPlugin("org.scalameta"                     % "sbt-scalafmt"     % "2.4.3")
addSbtPlugin("org.scalameta"                     % "sbt-mdoc"         % "2.2.23")
addSbtPlugin("org.scoverage"                     % "sbt-scoverage"    % "1.9.0")

libraryDependencies += "org.snakeyaml" % "snakeyaml-engine" % "2.3"
