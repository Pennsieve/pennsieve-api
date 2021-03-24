cancelable in Global := true

// common settings
ThisBuild / resolvers ++= Seq(
  "Pennsieve Releases" at "https://nexus.pennsieve.cc/repository/maven-releases",
  "Pennsieve Snapshots" at "https://nexus.pennsieve.cc/repository/maven-snapshots",
  Resolver.sonatypeRepo("snapshots"),
  Resolver.bintrayRepo("commercetools", "maven")
)

ThisBuild / credentials += Credentials(
  "Sonatype Nexus Repository Manager",
  "nexus.pennsieve.cc",
  sys.env("PENNSIEVE_NEXUS_USER"),
  sys.env("PENNSIEVE_NEXUS_PW")
)

// Temporarily disable Coursier because parallel builds fail on Jenkins.
// See https://app.clickup.com/t/a8ned9
ThisBuild / useCoursier := false

ThisBuild / scalaVersion := "2.12.11"
ThisBuild / organization := "com.pennsieve"
ThisBuild / organizationName := "University of Pennsylvania"
ThisBuild / licenses := List("Apache-2.0" -> new URL("https://www.apache.org/licenses/LICENSE-2.0.txt"))
ThisBuild / startYear := Some(2021)

ThisBuild / version := sys.props.get("version").getOrElse("bootstrap-SNAPSHOT")

val publishToNexus =
  settingKey[Option[Resolver]]("Pennsieve Nexus repository resolver")

ThisBuild / publishToNexus := {
  val nexus = "https://nexus.pennsieve.cc/repository"
  if (isSnapshot.value) {
    Some("Nexus Realm" at s"$nexus/maven-snapshots")
  } else {
    Some("Nexus Realm" at s"$nexus/maven-releases")
  }
}

val remoteCacheLocation =
  sys.props.get("remote-cache").getOrElse("/tmp/sbt/pennsieve-api")
ThisBuild / pushRemoteCacheTo := Some(
  MavenCache("local-cache", file(remoteCacheLocation))
)

ThisBuild / scalafmtOnCompile := true

// Run tests in a separate JVM to prevent resource leaks.
ThisBuild / Test / fork := true

lazy val akkaVersion = "2.6.5"
lazy val akkaCirceVersion = "1.27.0"
lazy val akkaHttpVersion = "10.1.11"

lazy val akkaStreamContribVersion = "0.11"
lazy val alpakkaVersion = "2.0.2"
lazy val auditMiddlewareVersion = "1.0.0"
lazy val authMiddlewareVersion = "4.2.3"

lazy val authyVersion = "1.5.1"
lazy val awsVersion = "1.11.931"
lazy val awsV2Version = "2.15.58"
lazy val catsVersion = "1.1.0"
lazy val circeVersion = "0.11.1"
lazy val circeDerivationVersion = "0.11.0-M3"
lazy val ficusVersion = "1.4.0"
lazy val flywayVersion = "4.2.0"
lazy val json4sVersion = "3.5.2"
lazy val jettyVersion = "9.1.3.v20140225"
lazy val postgresVersion = "42.1.4"
lazy val scalatraVersion = "2.6.5"
lazy val scalatestVersion = "3.0.3"
lazy val slickVersion = "3.3.3"
lazy val testContainersVersion = "0.38.8"
lazy val utilitiesVersion = "3-cd7539b"
lazy val jobSchedulingServiceClientVersion = "3-1a58954"
lazy val serviceUtilitiesVersion = "6-2a4488a"
lazy val discoverServiceClientVersion = "6-c26717e"
lazy val doiServiceClientVersion = "3-9436155"
lazy val timeseriesCoreVersion = "4-d8f62a4"
lazy val commonsIoVersion = "2.6"
lazy val enumeratumVersion = "1.5.14"

lazy val unwantedDependencies = Seq(
  ExclusionRule("commons-logging", "commons-logging"),
  // Drop core-models pulled in as a transitive dependency by clients
  ExclusionRule("com.pennsieve", "core-models_2.12"),
  ExclusionRule("com.typesafe.akka", "akka-protobuf-v3_2.12")
)

import sbtassembly.MergeStrategy
lazy val defaultMergeStrategy = settingKey[String => MergeStrategy](
  "Default mapping from archive member path to merge strategy. Used by all subprojects that build fat JARS"
)

ThisBuild / defaultMergeStrategy := {
  case PathList("META-INF", _ @_*) => MergeStrategy.discard
  case PathList("PropertyList-1.0.dtd", _ @_*) => MergeStrategy.last
  case PathList("codegen-resources", "customization.config", _ @_*) =>
    MergeStrategy.discard
  case PathList("codegen-resources", "examples-1.json", _ @_*) =>
    MergeStrategy.discard
  case PathList("codegen-resources", "paginators-1.json", _ @_*) =>
    MergeStrategy.discard
  case PathList("codegen-resources", "service-2.json", _ @_*) =>
    MergeStrategy.discard
  case PathList("codegen-resources", "waiters-2.json", _ @_*) =>
    MergeStrategy.discard
  case PathList("com", "google", "common", _ @_*) => MergeStrategy.first
  case PathList("com", "sun", _ @_*) => MergeStrategy.last
  case PathList("common-version-info.properties") => MergeStrategy.last
  case PathList("contribs", "mx", _ @_*) => MergeStrategy.last
  case PathList("core-default.xml") => MergeStrategy.last
  case PathList("digesterRules.xml") => MergeStrategy.last
  case PathList("groovy", _ @_*) => MergeStrategy.first
  case PathList("groovyjarjarcommonscli", _ @_*) => MergeStrategy.first
  case PathList("javax", _ @_*) => MergeStrategy.last
  case PathList("logback", _ @_*) => MergeStrategy.filterDistinctLines
  case PathList("logback.xml", _ @_*) => MergeStrategy.first
  case PathList("mime.types") => MergeStrategy.last
  case PathList("module-info.class") => MergeStrategy.discard
  case PathList("org", "apache", _ @_*) => MergeStrategy.last
  case PathList("org", "codehaus", _ @_*) => MergeStrategy.first
  case PathList("overview.html", _ @_*) => MergeStrategy.last
  case PathList("properties.dtd", _ @_*) => MergeStrategy.last
  case x => MergeStrategy.defaultMergeStrategy(x)
}

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-language:postfixOps",
    "-language:implicitConversions",
    "-Xmax-classfile-name",
    "100",
    "-feature",
    "-deprecation",
    "-Ypartial-unification"
  ),
  test in assembly := {},
  libraryDependencies ++= Seq(
    "org.slf4j" % "slf4j-api" % "1.7.25",
    "org.slf4j" % "jul-to-slf4j" % "1.7.25",
    "org.slf4j" % "jcl-over-slf4j" % "1.7.25",
    "org.slf4j" % "log4j-over-slf4j" % "1.7.25",
    "io.circe" %% "circe-java8" % circeVersion,
    "ch.qos.logback" % "logback-classic" % "1.2.3",
    "ch.qos.logback" % "logback-core" % "1.2.3",
    "net.logstash.logback" % "logstash-logback-encoder" % "5.2",
    "com.iheart" %% "ficus" % ficusVersion,
    "com.typesafe.scala-logging" %% "scala-logging" % "3.7.2",
    "org.postgresql" % "postgresql" % postgresVersion,
    "com.typesafe.slick" %% "slick" % slickVersion,
    "com.typesafe.slick" %% "slick-hikaricp" % slickVersion,
    "org.scala-lang.modules" %% "scala-java8-compat" % "0.9.1"
  ),
  excludeDependencies ++= unwantedDependencies
)

lazy val coreApiSharedSettings = Seq(
  resolvers ++= Seq(
    "Local Maven Repository" at "file://" + Path.userHome.absolutePath + "/.m2/repository",
    Resolver.sonatypeRepo("snapshots"),
    Resolver.sonatypeRepo("releases"),
    Resolver.jcenterRepo,
    "The New Motion Repository" at "https://nexus.thenewmotion.com/content/repositories/releases-public",
  ),
  libraryDependencies ++= Seq(
    "com.pennsieve" %% "audit-middleware" % auditMiddlewareVersion,
    "org.json4s" %% "json4s-jackson" % json4sVersion,
    "org.json4s" %% "json4s-ext" % json4sVersion,
    "commons-io" % "commons-io" % commonsIoVersion,
    "org.scalikejdbc" %% "scalikejdbc" % "2.5.0",
    "org.scalikejdbc" %% "scalikejdbc-config" % "2.5.0",
    "com.typesafe.slick" %% "slick" % slickVersion,
    "com.typesafe.slick" %% "slick-hikaricp" % slickVersion,
    "com.typesafe.akka" %% "akka-stream" % akkaVersion,
    "org.typelevel" %% "cats-core" % catsVersion,
    "com.github.tminglei" %% "slick-pg" % "0.17.3",
    "com.github.tminglei" %% "slick-pg_circe-json" % "0.17.3",
    "com.rms.miu" %% "slick-cats" % "0.7.1",
    // Testing deps
    "com.dimafeng" %% "testcontainers-scala" % testContainersVersion % Test,
    "org.scalatest" %% "scalatest" % scalatestVersion % Test,
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test,
    "org.scalikejdbc" %% "scalikejdbc-test" % "2.5.0" % Test,
  ),
  excludeDependencies ++= unwantedDependencies
)

// API settings
lazy val apiSettings = Seq(
  name := "pennsieve-api",
  containerPort := 5000,
  javaOptions in Jetty ++= Seq(
    "-Xdebug",
    "-Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=8000"
  ),
  buildOptions in docker := BuildOptions(cache = false),
  dockerfile in docker := {
    val warFile: File = sbt.Keys.`package`.value
    new SecureDockerfile("pennsieve/tomcat-cloudwrap:8-jre-alpine-0.5.9") {
      copy(warFile, "webapps/ROOT.war", chown = "pennsieve:pennsieve")
      run("mkdir", "newrelic")
      run(
        "wget",
        "-qO",
        "newrelic/newrelic.jar",
        "http://download.newrelic.com/newrelic/java-agent/newrelic-agent/current/newrelic.jar"
      )
      run(
        "wget",
        "-qO",
        "newrelic/newrelic.yml",
        "http://download.newrelic.com/newrelic/java-agent/newrelic-agent/current/newrelic.yml"
      )
      cmd("--service", "api", "exec", "catalina.sh", "run")
    }
  },
  imageNames in docker := Seq(
    ImageName(s"pennsieve/api:latest"),
    ImageName(
      s"pennsieve/api:${sys.props.getOrElse("docker-version", version.value)}"
    )
  ),
  excludeFilter := HiddenFileFilter -- ".ebextensions",
  libraryDependencies ++= Seq(
    "commons-codec" % "commons-codec" % "1.7",
    "com.authy" % "authy-java" % authyVersion,
    "com.pennsieve" %% "audit-middleware" % auditMiddlewareVersion,
    "com.pennsieve" %% "auth-middleware" % authMiddlewareVersion,
    "com.pennsieve" %% "doi-service-client" % doiServiceClientVersion,
    "com.pennsieve" %% "discover-service-client" % discoverServiceClientVersion,
    "com.typesafe.akka" %% "akka-actor" % akkaVersion,
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
    "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
    "com.typesafe.akka" %% "akka-stream-typed" % akkaVersion,
    "javax.servlet" % "javax.servlet-api" % "3.1.0" % "provided",
    "org.scalatra" %% "scalatra" % scalatraVersion,
    "org.scalatra" %% "scalatra-json" % scalatraVersion,
    "org.scalatra" %% "scalatra-swagger" % scalatraVersion,
    "org.typelevel" %% "mouse" % "0.16",
    "io.scalaland" %% "chimney" % "0.2.1",
    // Test deps
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test,
    "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion % Test,
    "org.scalatra" %% "scalatra-scalatest" % scalatraVersion % Test
  ),
  excludeDependencies ++= unwantedDependencies :+ ExclusionRule(
    "javax.ws.rs",
    "jsr311-api"
  )
)

// core settings
lazy val coreSettings = Seq(
  name := "pennsieve-core",
  publishTo := publishToNexus.value,
  publishArtifact in Test := true,
  testOptions in Test += Tests.Argument(TestFrameworks.ScalaTest, "-oD"),
  publishMavenStyle := true,
  scalacOptions ++= Seq("-language:higherKinds"),
  libraryDependencies ++= Seq(
    "com.pennsieve" %% "auth-middleware" % authMiddlewareVersion,
    "com.pennsieve" %% "job-scheduling-service-client" % jobSchedulingServiceClientVersion,
    "com.pennsieve" %% "service-utilities" % serviceUtilitiesVersion,
    "com.pennsieve" %% "utilities" % utilitiesVersion,
    "com.authy" % "authy-java" % authyVersion % Test,
    "commons-codec" % "commons-codec" % "1.10",
    "commons-validator" % "commons-validator" % "1.6",
    "com.chuusai" %% "shapeless" % "2.3.3",
    "com.beachape" %% "enumeratum" % enumeratumVersion,
    "com.beachape" %% "enumeratum-circe" % enumeratumVersion,
    "com.beachape" %% "enumeratum-json4s" % enumeratumVersion,
    "io.circe" %% "circe-core" % circeVersion,
    "io.circe" %% "circe-generic" % circeVersion,
    "io.circe" %% "circe-generic-extras" % circeVersion,
    "io.circe" %% "circe-parser" % circeVersion,
    "io.circe" %% "circe-shapes" % circeVersion,
    "io.circe" %% "circe-derivation" % circeDerivationVersion,
    "io.github.nremond" %% "pbkdf2-scala" % "0.6",
    "net.debasishg" %% "redisclient" % "3.30",
    "io.swagger" %% "swagger-scala-module" % "1.0.6",
    "com.amazonaws" % "aws-java-sdk-core" % awsVersion,
    "com.amazonaws" % "aws-java-sdk-ecs" % awsVersion,
    "com.amazonaws" % "aws-java-sdk-kms" % awsVersion,
    "com.amazonaws" % "aws-java-sdk-s3" % awsVersion,
    "com.amazonaws" % "aws-java-sdk-ses" % awsVersion,
    "com.amazonaws" % "aws-java-sdk-ssm" % awsVersion,
    "software.amazon.awssdk" % "sns" % awsV2Version,
    "software.amazon.awssdk" % "sqs" % awsV2Version,
    "software.amazon.awssdk" % "cognitoidentityprovider" % awsV2Version,
    "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
    "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
    "com.typesafe.akka" %% "akka-stream-typed" % akkaVersion,
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion
  ),
  excludeDependencies ++= unwantedDependencies
)

// jobs settings
lazy val jobsSettings = Seq(
  name := "jobs",
  libraryDependencies ++= Seq(
    "com.pennsieve" %% "audit-middleware" % auditMiddlewareVersion,
    "com.pennsieve" %% "timeseries-core" % timeseriesCoreVersion,
    "com.typesafe.akka" %% "akka-actor" % akkaVersion,
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
    "com.typesafe.akka" %% "akka-stream" % akkaVersion,
    "com.typesafe.akka" %% "akka-stream-contrib" % akkaStreamContribVersion,
    "io.circe" %% "circe-core" % circeVersion,
    "io.circe" %% "circe-generic" % circeVersion,
    "io.circe" %% "circe-parser" % circeVersion,
    "io.circe" %% "circe-java8" % circeVersion,
    "org.typelevel" %% "cats-core" % catsVersion,
    // testing deps
    "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion % Test,
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test,
    "org.scalatest" %% "scalatest" % scalatestVersion % Test
  ),
  excludeDependencies ++= unwantedDependencies,
  dockerfile in docker := {
    val artifact: File = assembly.value
    val artifactTargetPath = s"/app/${artifact.name}"
    new SecureDockerfile("pennsieve/java-cloudwrap:10-jre-slim-0.5.9") {
      copy(artifact, artifactTargetPath, chown = "pennsieve:pennsieve")
      cmd("--service", "jobs", "exec", "java", "-jar", artifactTargetPath)
    }
  },
  imageNames in docker := Seq(
    ImageName("pennsieve/jobs:latest"),
    ImageName(
      s"pennsieve/jobs:${sys.props.getOrElse("docker-version", version.value)}"
    )
  ),
  assemblyExcludedJars in assembly := {
    val cp = (fullClasspath in assembly).value
    cp filter { _.data.getName == "groovy-2.4.11.jar" }
  },
  assemblyMergeStrategy in assembly := defaultMergeStrategy.value
)

lazy val adminSettings = Seq(
  name := "admin",
  publishTo := publishToNexus.value,
  libraryDependencies ++= Seq(
    "com.pennsieve" %% "discover-service-client" % discoverServiceClientVersion,
    "com.github.swagger-akka-http" %% "swagger-akka-http" % "0.14.0",
    "com.iheart" %% "ficus" % ficusVersion,
    "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
    "io.circe" %% "circe-java8" % circeVersion,
    // needed to work correctly on JVM9+ -- this should be moved to bf-akka-http once all bf-akka-http users use JVM9+
    "javax.xml.bind" % "jaxb-api" % "2.2.8",
    // testing deps
    "org.scalatest" %% "scalatest" % scalatestVersion % Test,
    "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion % Test,
    "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion % Test
  ),
  excludeDependencies ++= unwantedDependencies,
  dockerfile in docker := {
    val artifact: File = assembly.value
    val artifactTargetPath = s"/app/${artifact.name}"
    new SecureDockerfile("pennsieve/java-cloudwrap:10-jre-slim-0.5.9") {
      copy(artifact, artifactTargetPath, chown = "pennsieve:pennsieve")
      cmd("--service", "admin", "exec", "java", "-jar", artifactTargetPath)
    }
  },
  imageNames in docker := Seq(ImageName("pennsieve/admin:latest")),
  assemblyMergeStrategy in assembly := defaultMergeStrategy.value
)

lazy val authorizationServiceSettings = Seq(
  name := "authorization-service",
  publishTo := publishToNexus.value,
  libraryDependencies ++= Seq(
    "com.authy" % "authy-java" % authyVersion,
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
    "com.iheart" %% "ficus" % ficusVersion,
    "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
    "io.circe" %% "circe-java8" % circeVersion,
    "com.pennsieve" %% "auth-middleware" % authMiddlewareVersion,
    // testing deps
    "org.scalatest" %% "scalatest" % scalatestVersion % Test,
    "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion % Test,
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test,
    "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion % Test
  ),
  excludeDependencies ++= unwantedDependencies,
  dockerfile in docker := {
    val artifact: File = assembly.value
    val artifactTargetPath = s"/app/${artifact.name}"
    new SecureDockerfile("pennsieve/java-cloudwrap:10-jre-slim-0.5.9") {
      copy(artifact, artifactTargetPath, chown = "pennsieve:pennsieve")
      copy(
        baseDirectory.value / "bin" / "run.sh",
        "/app/run.sh",
        chown = "pennsieve:pennsieve"
      )
      run(
        "wget",
        "-qO",
        "/app/newrelic.jar",
        "http://download.newrelic.com/newrelic/java-agent/newrelic-agent/current/newrelic.jar"
      )
      env("RUST_BACKTRACE", "1")
      cmd(
        "--service",
        "authorization-service",
        "exec",
        "/app/run.sh",
        artifactTargetPath
      )
    }
  },
  imageNames in docker := Seq(
    ImageName("pennsieve/authorization-service:latest")
  ),
  assemblyMergeStrategy in assembly := defaultMergeStrategy.value
)

lazy val bfAkkaHttpSettings = Seq(
  name := "bf-akka-http",
  publishTo := publishToNexus.value,
  publishArtifact in Test := true,
  publishMavenStyle := true,
  libraryDependencies ++= Seq(
    "com.github.swagger-akka-http" %% "swagger-akka-http" % "0.14.0",
    "com.iheart" %% "ficus" % ficusVersion,
    "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
    "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
    "com.typesafe.akka" %% "akka-stream-typed" % akkaVersion,
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
    "io.circe" %% "circe-java8" % circeVersion,
    // testing deps
    "org.scalatest" %% "scalatest" % scalatestVersion % Test,
    "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion % Test
  )
)

lazy val migrationsSettings = Seq(
  name := "migrations",
  publishTo := publishToNexus.value,
  resolvers ++= Seq("Flyway" at "https://flywaydb.org/repo"),
  libraryDependencies ++= Seq(
    "com.iheart" %% "ficus" % ficusVersion,
    "org.flywaydb" % "flyway-core" % flywayVersion,
    "org.postgresql" % "postgresql" % postgresVersion
  ),
  dockerfile in docker := {
    val artifact: File = assembly.value
    val artifactTargetPath = s"/app/${artifact.name}"
    new SecureDockerfile("pennsieve/java-cloudwrap:8-jre-alpine-0.5.9") {
      copy(artifact, artifactTargetPath, chown = "pennsieve:pennsieve")
      // build-postgres.sh script needs a stable JAR name to run without Cloudwrap
      run("ln", "-s", artifactTargetPath, "/app/migrations.jar")
      cmd("--service", "migrations", "exec", "java", "-jar", artifactTargetPath)
    }
  },
  imageNames in docker := Seq(ImageName("pennsieve/migrations:latest")),
  assemblyMergeStrategy in assembly := defaultMergeStrategy.value
)

lazy val unusedOrganizationMigrationSettings = Seq(
  name := "unused-organization-migration",
  libraryDependencies ++= Seq(),
  excludeDependencies ++= unwantedDependencies,
  dockerfile in docker := {
    val artifact: File = assembly.value
    val artifactTargetPath = s"/app/${artifact.name}"
    new SecureDockerfile("pennsieve/java-cloudwrap:8-jre-alpine-0.5.9") {
      copy(artifact, artifactTargetPath, chown = "pennsieve:pennsieve")
      cmd("--service", "migrations", "exec", "java", "-jar", artifactTargetPath)
    }
  },
  imageNames in docker := Seq(
    ImageName("pennsieve/unused-organization-migration:latest")
  ),
  assemblyMergeStrategy in assembly := defaultMergeStrategy.value
)

lazy val etlDataCLISettings = Seq(
  name := "etl-data-cli",
  libraryDependencies ++= Seq(
    "com.github.scopt" %% "scopt" % "3.7.0",
    "io.circe" %% "circe-java8" % circeVersion
  ),
  excludeDependencies ++= unwantedDependencies,
  dockerfile in docker := {
    val artifact: File = assembly.value
    val script: File = new File("etl-data-cli/etl-data.sh")
    val artifactTargetPath = s"/app/${artifact.name}"

    new SecureDockerfile("pennsieve/base-processor-java-python:6-43b7408") {

      env("ARTIFACT_TARGET_PATH", artifactTargetPath)
      copy(artifact, artifactTargetPath)
      copy(script, "/app/etl-data")
      run("mkdir", "-p", "/root/.postgresql")
      run(
        "wget",
        "-qO",
        "/root/.postgresql/root.crt",
        "https://s3.amazonaws.com/rds-downloads/rds-ca-2019-root.pem"
      )
      entryPoint("")
    }
  },
  imageNames in docker := Seq(ImageName("pennsieve/etl-data-cli:latest")),
  assemblyMergeStrategy in assembly := defaultMergeStrategy.value
)

lazy val uploadsConsumerSettings = Seq(
  name := "uploads-consumer",
  libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
    "com.typesafe.akka" %% "akka-stream-contrib" % akkaStreamContribVersion,
    "com.dimafeng" %% "testcontainers-scala" % testContainersVersion % Test
  ),
  excludeDependencies ++= unwantedDependencies,
  dockerfile in docker := {
    val artifact: File = assembly.value
    val artifactTargetPath = s"/app/${artifact.name}"
    new SecureDockerfile("pennsieve/openjdk:8-alpine3.9") {
      copy(artifact, artifactTargetPath, chown = "pennsieve:pennsieve")
      cmd("java", "-jar", artifactTargetPath)
    }
  },
  imageNames in docker := Seq(ImageName("pennsieve/uploads-consumer:latest")),
  assemblyMergeStrategy in assembly := defaultMergeStrategy.value
)

lazy val bfAkkaSettings = Seq(
  name := "bf-akka",
  libraryDependencies ++= Seq(
    "com.pennsieve" %% "utilities" % utilitiesVersion,
    "com.typesafe.akka" %% "akka-stream" % akkaVersion,
    "com.lightbend.akka" %% "akka-stream-alpakka-sns" % alpakkaVersion,
    "com.lightbend.akka" %% "akka-stream-alpakka-sqs" % alpakkaVersion,
    "com.typesafe.akka" %% "akka-stream-contrib" % akkaStreamContribVersion,
    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.0",
    "io.circe" %% "circe-core" % circeVersion,
    "org.typelevel" %% "cats-core" % catsVersion
  )
)

lazy val coreClientsSettings = Seq(
  name := "core-clients",
  publishTo := publishToNexus.value,
  publishMavenStyle := true,
  libraryDependencies ++= Seq(
    "com.pennsieve" %% "utilities" % utilitiesVersion,
    "de.heikoseeberger" %% "akka-http-circe" % akkaCirceVersion,
    "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
    "io.circe" %% "circe-core" % circeVersion,
    "io.circe" %% "circe-generic" % circeVersion,
    "io.circe" %% "circe-generic-extras" % circeVersion
  )
)

lazy val coreModelsSettings = Seq(
  name := "core-models",
  publishTo := publishToNexus.value,
  publishMavenStyle := true,
  libraryDependencies ++= Seq(
    "commons-io" % "commons-io" % commonsIoVersion,
    "com.beachape" %% "enumeratum" % enumeratumVersion,
    "com.beachape" %% "enumeratum-circe" % enumeratumVersion,
    "io.circe" %% "circe-core" % circeVersion,
    "io.circe" %% "circe-generic-extras" % circeVersion,
    "io.circe" %% "circe-java8" % circeVersion,
    "io.circe" %% "circe-shapes" % circeVersion,
    "io.circe" %% "circe-derivation" % circeDerivationVersion,
    "io.circe" %% "circe-parser" % circeVersion,
    "org.scalatest" %% "scalatest" % scalatestVersion % Test,
    "com.google.guava" % "guava" % "29.0-jre"
  )
)

lazy val bfAwsSettings = Seq(
  name := "bf-aws",
  publishTo := publishToNexus.value,
  publishMavenStyle := true,
  libraryDependencies ++= Seq(
    "com.amazonaws" % "aws-java-sdk-athena" % awsVersion,
    "com.amazonaws" % "aws-java-sdk-core" % awsVersion,
    "com.amazonaws" % "aws-java-sdk-lambda" % awsVersion,
    "com.amazonaws" % "aws-java-sdk-s3" % awsVersion,
    "software.amazon.awssdk" % "auth" % awsV2Version
  )
)

lazy val discoverPublishSettings = Seq(
  name := "discover-publish",
  libraryDependencies ++= Seq(
    "com.lightbend.akka" %% "akka-stream-alpakka-s3" % alpakkaVersion,
    "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
    "com.typesafe.akka" %% "akka-http-xml" % akkaHttpVersion,
    "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
    "com.typesafe.akka" %% "akka-stream-typed" % akkaVersion,
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
    "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion % Test
  ),
  excludeDependencies ++= unwantedDependencies,
  dockerfile in docker := {
    val artifact: File = assembly.value
    val artifactTargetPath = s"/app/${artifact.name}"
    new SecureDockerfile("pennsieve/openjdk:8-alpine3.9") {
      copy(artifact, artifactTargetPath, chown = "pennsieve:pennsieve")
      cmd("java", "-jar", artifactTargetPath)
    }
  },
  imageNames in docker := Seq(ImageName("pennsieve/discover-publish:latest")),
  assemblyMergeStrategy in assembly := defaultMergeStrategy.value
)

lazy val organizationStorageMigrationSettings =
  Seq(
    name := "organization-storage-migration",
    excludeDependencies ++= unwantedDependencies,
    dockerfile in docker := {
      val artifact: File = assembly.value
      val artifactTargetPath = s"/app/${artifact.name}"
      new SecureDockerfile("pennsieve/java-cloudwrap:8-jre-alpine-0.5.9") {
        copy(artifact, artifactTargetPath, chown = "pennsieve:pennsieve")
        cmd("--service", "admin", "exec", "java", "-jar", artifactTargetPath)
      }
    },
    imageNames in docker := Seq(
      ImageName("pennsieve/organization-storage-migration:latest")
    ),
    assemblyMergeStrategy in assembly := defaultMergeStrategy.value
  )

// Generates Scala template strings from raw HTML files.
// See project/CompileMessageTemplates.scala for plugin implementation.
lazy val messageTemplateSettings = Seq(
  name := "message-templates",
  publishTo := publishToNexus.value,
  messageTemplatesOutputPackage := "com.pennsieve.templates",
  messageTemplatesOutputFile := "GeneratedMessageTemplates.scala",
  messageTemplatesInputDirectory := "html",
  messageTemplatesInputGlob := "*.html"
)

// project definitions
lazy val core = project
  .settings(commonSettings: _*)
  .settings(coreApiSharedSettings: _*)
  .settings(coreSettings: _*)
  .dependsOn(`core-models`, `bf-aws`, `message-templates`, migrations % "test->compile")
  .enablePlugins(AutomateHeaderPlugin)

lazy val admin = project
  .dependsOn(
    core % "test->test;compile->compile",
    `bf-akka-http` % "test->test;compile->compile"
  )
  .enablePlugins(sbtdocker.DockerPlugin)
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings: _*)
  .settings(adminSettings: _*)

lazy val `authorization-service` = project
  .dependsOn(
    core % "test->test;compile->compile",
    `bf-akka-http` % "test->test;compile->compile"
  )
  .enablePlugins(sbtdocker.DockerPlugin)
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings: _*)
  .settings(authorizationServiceSettings: _*)

lazy val `bf-akka-http` = project
  .dependsOn(core % "test->test;compile->compile")
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings: _*)
  .settings(bfAkkaHttpSettings: _*)

lazy val api = project
  .dependsOn(core % "test->test;compile->compile")
  .enablePlugins(JettyPlugin, sbtdocker.DockerPlugin)
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings: _*)
  .settings(coreApiSharedSettings: _*)
  .settings(apiSettings: _*)

lazy val jobs = project
  .dependsOn(
    core % "test->test;compile->compile",
    `bf-akka` % "test->test;compile->compile",
    `bf-akka-http` % "test->test;compile->compile"
  )
  .enablePlugins(sbtdocker.DockerPlugin)
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings: _*)
  .settings(jobsSettings: _*)

lazy val migrations = project
  .enablePlugins(sbtdocker.DockerPlugin)
  .enablePlugins(AutomateHeaderPlugin)
  .settings(migrationsSettings: _*)

lazy val `uploads-consumer` = project
  .dependsOn(
    core % "test->test;compile->compile",
    `bf-akka` % "test->test;compile->compile",
    `bf-akka-http` % "test->test;compile->compile"
  )
  .enablePlugins(sbtdocker.DockerPlugin)
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings: _*)
  .settings(uploadsConsumerSettings: _*)

lazy val `etl-data-cli` = project
  .dependsOn(core % "test->test;compile->compile")
  .enablePlugins(sbtdocker.DockerPlugin)
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings: _*)
  .settings(etlDataCLISettings: _*)

lazy val `bf-akka` = project
  .settings(bfAkkaSettings)
  .dependsOn(core % "test->test;compile->compile")
  .enablePlugins(AutomateHeaderPlugin)

lazy val `core-models` = project
  .settings(coreModelsSettings)
  .enablePlugins(AutomateHeaderPlugin)

lazy val `core-clients` = project
  .settings(coreClientsSettings)
  .dependsOn(`core-models`)
  .enablePlugins(AutomateHeaderPlugin)

lazy val `bf-aws` = project
  .settings(bfAwsSettings)
  .enablePlugins(AutomateHeaderPlugin)

lazy val `discover-publish` = project
  .enablePlugins(sbtdocker.DockerPlugin)
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings: _*)
  .settings(discoverPublishSettings: _*)
  .dependsOn(core % "test->test;compile->compile")

lazy val `organization-storage-migration` = project
  .enablePlugins(sbtdocker.DockerPlugin)
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings: _*)
  .settings(organizationStorageMigrationSettings: _*)
  .dependsOn(core % "test->test;compile->compile")

lazy val `unused-organization-migration` = project
  .enablePlugins(sbtdocker.DockerPlugin)
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings: _*)
  .settings(unusedOrganizationMigrationSettings: _*)
  .dependsOn(core % "test->test;compile->compile")

lazy val `message-templates` = project
  .settings(messageTemplateSettings: _*)
  .enablePlugins(CompileMessageTemplates)
  .enablePlugins(AutomateHeaderPlugin)

lazy val root = (project in file("."))
  .aggregate(
    admin,
    `authorization-service`,
    api,
    `bf-akka-http`,
    core,
    jobs,
    `etl-data-cli`,
    `uploads-consumer`,
    `bf-akka`,
    `core-models`,
    `core-clients`,
    `bf-aws`,
    `discover-publish`,
    `unused-organization-migration`,
    `message-templates`
  )
  .settings(commonSettings: _*)
