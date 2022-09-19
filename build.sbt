import CrossCompilationUtil.{
  getScalacOptions,
  getVersion,
  handle212OnlyDependency
}
Global / cancelable := true

// common settings
ThisBuild / resolvers ++= Seq(
  "Pennsieve Releases" at "https://nexus.pennsieve.cc/repository/maven-releases",
  "Pennsieve Snapshots" at "https://nexus.pennsieve.cc/repository/maven-snapshots",
  Resolver.sonatypeRepo("snapshots")
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

lazy val scala212 = "2.12.11"
lazy val scala213 = "2.13.8"
lazy val supportedScalaVersions = List(scala212, scala213)

ThisBuild / scalaVersion := scala212
ThisBuild / organization := "com.pennsieve"
ThisBuild / organizationName := "University of Pennsylvania"
ThisBuild / licenses := List(
  "Apache-2.0" -> new URL("https://www.apache.org/licenses/LICENSE-2.0.txt")
)
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

lazy val akkaVersion = SettingKey[String]("akkaVersion")
lazy val akka212Version = "2.6.5"
lazy val akka213Version = "2.6.8"
ThisBuild / akkaVersion := akka212Version

lazy val akkaCirceVersion = SettingKey[String]("akkaCirceVersion")
lazy val akkaCirce212Version = "1.27.0"
lazy val akkaCirce213Version = "1.39.2"
// Uncomment this if 'akkaCirceVersion' gets used outside of core-clients
//ThisBuild / akkaCirceVersion := akkaCirce212Version

lazy val akkaHttpVersion = SettingKey[String]("akkaHttpVersion")
lazy val akkaHttp212Version = "10.1.11"
lazy val akkaHttp213Version = "10.2.7"
ThisBuild / akkaHttpVersion := akkaHttp212Version

lazy val akkaStreamContribVersion = "0.11"
lazy val alpakkaVersion = "2.0.2"

lazy val swaggerAkkaHttpVersion = SettingKey[String]("swaggerAkkaHttpVersion")
lazy val swaggerAkkaHttp212Version = "0.14.0"
lazy val swaggerAkkaHttp213Version = "1.4.0"
ThisBuild / swaggerAkkaHttpVersion := swaggerAkkaHttp212Version

lazy val auditMiddlewareVersion = "1.0.2"
lazy val authMiddlewareVersion = "5.1.3"

lazy val awsVersion = "1.11.931"
lazy val awsV2Version = "2.15.58"

lazy val catsVersion = SettingKey[String]("catsVersion")
lazy val cats212Version = "1.1.0"
lazy val cats213Version = "2.6.1"
ThisBuild / catsVersion := cats212Version

lazy val circeVersion = SettingKey[String]("circeVersion")
lazy val circe212Version = "0.11.1"
lazy val circe213Version = "0.14.1"
ThisBuild / circeVersion := circe212Version

lazy val circeDerivationVersion = SettingKey[String]("circeDerivationVersion")
lazy val circeDerivation212Version = "0.11.0-M3"
lazy val circeDerivation213Version = "0.13.0-M5"
ThisBuild / circeDerivationVersion := circeDerivation212Version

lazy val ficusVersion = SettingKey[String]("ficusVersion")
lazy val ficus212Version = "1.4.0"
lazy val ficus213Version = "1.5.2"

lazy val flywayVersion = "4.2.0"

lazy val json4sVersion = SettingKey[String]("json4sVersion")
lazy val json4s212Version = "3.5.2"
lazy val json4s213Version = "3.5.5"

lazy val jettyVersion = "9.1.3.v20140225"
lazy val postgresVersion = "42.1.4"
lazy val scalatraVersion = "2.7.1" //"2.6.5"//"2.8.2"

lazy val scalatestVersion = SettingKey[String]("scalatestVersion")
lazy val scalatest212Version = "3.0.3"
lazy val scalatest213Version = "3.2.11"
ThisBuild / scalatestVersion := scalatest212Version

lazy val scalikejdbcVersion = "3.5.0"

lazy val slickVersion = "3.3.3"

lazy val slickPgVersion = SettingKey[String]("slickPgVersion")
lazy val slickPg212Version = "0.17.3"
lazy val slickPg213Version = "0.20.3"

lazy val slickCatsVersion = SettingKey[String]("slickCatsVersion")
lazy val slickCats212Version = "0.7.1"
lazy val slickCats213Version = "0.10.4"

lazy val testContainersVersion = "0.40.1"
lazy val utilitiesVersion = "4-55953e4"
lazy val jobSchedulingServiceClientVersion = "6-3251c91"
lazy val serviceUtilitiesVersion = "8-9751ee3"
lazy val discoverServiceClientVersion = "36-32664eb"
lazy val doiServiceClientVersion = "12-756107b"
lazy val timeseriesCoreVersion = "6-487b00c"
lazy val commonsIoVersion = "2.6"

lazy val enumeratumVersion = SettingKey[String]("enumeratumVersion")
lazy val enumeratum212Version = "1.5.14"
lazy val enumeratum213Version = "1.7.0"

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
  scalacOptions ++= getScalacOptions(scalaVersion.value),
  assembly / test := {},
  circeVersion := getVersion(
    scalaVersion.value,
    circe212Version,
    circe213Version
  ),
  ficusVersion := getVersion(
    scalaVersion.value,
    ficus212Version,
    ficus213Version
  ),
  libraryDependencies ++= Seq(
    "org.slf4j" % "slf4j-api" % "1.7.25",
    "org.slf4j" % "jul-to-slf4j" % "1.7.25",
    "org.slf4j" % "jcl-over-slf4j" % "1.7.25",
    "org.slf4j" % "log4j-over-slf4j" % "1.7.25",
    "ch.qos.logback" % "logback-classic" % "1.2.3",
    "ch.qos.logback" % "logback-core" % "1.2.3",
    "net.logstash.logback" % "logstash-logback-encoder" % "5.2",
    "com.iheart" %% "ficus" % ficusVersion.value,
    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.4",
    "org.postgresql" % "postgresql" % postgresVersion,
    "com.typesafe.slick" %% "slick" % slickVersion,
    "com.typesafe.slick" %% "slick-hikaricp" % slickVersion,
    "org.scala-lang.modules" %% "scala-java8-compat" % "0.9.1"
  ),
  libraryDependencies ++= handle212OnlyDependency(
    scalaVersion.value,
    "io.circe" %% "circe-java8" % circeVersion.value
  ),
  excludeDependencies ++= unwantedDependencies
)

lazy val coreApiSharedSettings = Seq(
  resolvers ++= Seq(
    "Local Maven Repository" at "file://" + Path.userHome.absolutePath + "/.m2/repository",
    Resolver.sonatypeRepo("snapshots"),
    Resolver.sonatypeRepo("releases"),
    "The New Motion Repository" at "https://nexus.thenewmotion.com/content/repositories/releases-public"
  ),
  json4sVersion := getVersion(
    scalaVersion.value,
    json4s212Version,
    json4s213Version
  ),
  akkaVersion := getVersion(scalaVersion.value, akka212Version, akka213Version),
  scalatestVersion := scalatest213Version,
  slickPgVersion := getVersion(
    scalaVersion.value,
    slickPg212Version,
    slickPg213Version
  ),
  slickCatsVersion := getVersion(
    scalaVersion.value,
    slickCats212Version,
    slickCats213Version
  ),
  catsVersion := getVersion(scalaVersion.value, cats212Version, cats213Version),
  libraryDependencies ++= Seq(
    "com.pennsieve" %% "audit-middleware" % auditMiddlewareVersion,
    "org.json4s" %% "json4s-jackson" % json4sVersion.value,
    "org.json4s" %% "json4s-ext" % json4sVersion.value,
    "commons-io" % "commons-io" % commonsIoVersion,
    "org.scalikejdbc" %% "scalikejdbc" % scalikejdbcVersion,
    "org.scalikejdbc" %% "scalikejdbc-config" % scalikejdbcVersion,
    "com.typesafe.slick" %% "slick" % slickVersion,
    "com.typesafe.slick" %% "slick-hikaricp" % slickVersion,
    "com.typesafe.akka" %% "akka-stream" % akkaVersion.value,
    "org.typelevel" %% "cats-core" % catsVersion.value,
    "com.github.tminglei" %% "slick-pg" % slickPgVersion.value,
    "com.github.tminglei" %% "slick-pg_circe-json" % slickPgVersion.value,
    "com.rms.miu" %% "slick-cats" % slickCatsVersion.value,
    // Testing deps
    "com.dimafeng" %% "testcontainers-scala" % testContainersVersion % Test,
    "org.scalatest" %% "scalatest" % scalatestVersion.value % Test,
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion.value % Test,
    "org.scalikejdbc" %% "scalikejdbc-test" % scalikejdbcVersion % Test
  ),
  excludeDependencies ++= unwantedDependencies
)

// API settings
lazy val apiSettings = Seq(
  name := "pennsieve-api",
  scalaVersion := scala213,
  containerPort := 5000,
  Jetty / javaOptions ++= Seq(
    "-Xdebug",
    "-Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=8000"
  ),
  docker / buildOptions := BuildOptions(cache = false),
  docker / dockerfile := {
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
  docker / imageNames := Seq(
    ImageName(s"pennsieve/api:latest"),
    ImageName(
      s"pennsieve/api:${sys.props.getOrElse("docker-version", version.value)}"
    )
  ),
  excludeFilter := HiddenFileFilter -- ".ebextensions",
  libraryDependencies ++= Seq(
    "commons-codec" % "commons-codec" % "1.7",
    "com.pennsieve" %% "audit-middleware" % auditMiddlewareVersion,
    "com.pennsieve" %% "auth-middleware" % authMiddlewareVersion,
    "com.pennsieve" %% "doi-service-client" % doiServiceClientVersion,
    "com.pennsieve" %% "discover-service-client" % discoverServiceClientVersion,
    "com.typesafe.akka" %% "akka-actor" % akkaVersion.value,
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion.value,
    "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion.value,
    "com.typesafe.akka" %% "akka-stream-typed" % akkaVersion.value,
    "javax.servlet" % "javax.servlet-api" % "3.1.0" % "provided",
    "org.scalatra" %% "scalatra" % scalatraVersion,
    "org.scalatra" %% "scalatra-json" % scalatraVersion,
    "org.scalatra" %% "scalatra-swagger" % scalatraVersion,
    "org.typelevel" %% "mouse" % "0.22",
    "io.scalaland" %% "chimney" % "0.6.1",
    // Test deps
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion.value % Test,
    "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion.value % Test,
    "org.scalatra" %% "scalatra-scalatest" % scalatraVersion % Test
  ),
  excludeDependencies ++= unwantedDependencies :+ ExclusionRule(
    "javax.ws.rs",
    "jsr311-api"
  ),
)

// core settings
lazy val coreSettings = Seq(
  name := "pennsieve-core",
  publishTo := publishToNexus.value,
  Test / publishArtifact := true,
  Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oD"),
  publishMavenStyle := true,
  crossScalaVersions := supportedScalaVersions,
  circeVersion := getVersion(
    scalaVersion.value,
    circe212Version,
    circe213Version
  ),
  enumeratumVersion := getVersion(
    scalaVersion.value,
    enumeratum212Version,
    enumeratum213Version
  ),
  circeDerivationVersion := getVersion(
    scalaVersion.value,
    circeDerivation212Version,
    circeDerivation213Version
  ),
  akkaVersion := getVersion(scalaVersion.value, akka212Version, akka213Version),
  akkaHttpVersion := getVersion(
    scalaVersion.value,
    akkaHttp212Version,
    akkaHttp213Version
  ),
  scalacOptions ++= Seq("-language:higherKinds"),
  libraryDependencies ++= Seq(
    "com.pennsieve" %% "auth-middleware" % authMiddlewareVersion,
    "com.pennsieve" %% "job-scheduling-service-client" % jobSchedulingServiceClientVersion,
    "com.pennsieve" %% "service-utilities" % serviceUtilitiesVersion,
    "com.pennsieve" %% "utilities" % utilitiesVersion,
    "commons-codec" % "commons-codec" % "1.10",
    "commons-validator" % "commons-validator" % "1.6",
    "com.chuusai" %% "shapeless" % "2.3.3",
    "com.beachape" %% "enumeratum" % enumeratumVersion.value,
    "com.beachape" %% "enumeratum-circe" % enumeratumVersion.value,
    "com.beachape" %% "enumeratum-json4s" % enumeratumVersion.value,
    "io.circe" %% "circe-core" % circeVersion.value,
    "io.circe" %% "circe-generic" % circeVersion.value,
    "io.circe" %% "circe-generic-extras" % circeVersion.value,
    "io.circe" %% "circe-parser" % circeVersion.value,
    "io.circe" %% "circe-shapes" % circeVersion.value,
    "io.circe" %% "circe-derivation" % circeDerivationVersion.value,
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
    "com.typesafe.akka" %% "akka-http" % akkaHttpVersion.value,
    "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion.value,
    "com.typesafe.akka" %% "akka-stream-typed" % akkaVersion.value,
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion.value,
    "com.auth0" % "jwks-rsa" % "0.8.3",
    "org.scala-lang.modules" %% "scala-collection-compat" % "2.8.1",
    "com.nimbusds" % "nimbus-jose-jwt" % "9.7" % Test
  ),
  excludeDependencies ++= unwantedDependencies
)

// jobs settings
lazy val jobsSettings = Seq(
  name := "jobs",
  libraryDependencies ++= Seq(
    "com.pennsieve" %% "audit-middleware" % auditMiddlewareVersion,
    "com.pennsieve" %% "timeseries-core" % timeseriesCoreVersion,
    "com.typesafe.akka" %% "akka-actor" % akkaVersion.value,
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion.value,
    "com.typesafe.akka" %% "akka-stream" % akkaVersion.value,
    "com.typesafe.akka" %% "akka-stream-contrib" % akkaStreamContribVersion,
    "io.circe" %% "circe-core" % circeVersion.value,
    "io.circe" %% "circe-generic" % circeVersion.value,
    "io.circe" %% "circe-parser" % circeVersion.value,
    "io.circe" %% "circe-java8" % circeVersion.value,
    "org.typelevel" %% "cats-core" % catsVersion.value,
    // testing deps
    "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion.value % Test,
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion.value % Test,
    "org.scalatest" %% "scalatest" % scalatestVersion.value % Test
  ),
  excludeDependencies ++= unwantedDependencies,
  docker / dockerfile := {
    val artifact: File = assembly.value
    val artifactTargetPath = s"/app/${artifact.name}"
    new SecureDockerfile("pennsieve/java-cloudwrap:10-jre-slim-0.5.9") {
      copy(artifact, artifactTargetPath, chown = "pennsieve:pennsieve")
      cmd("--service", "jobs", "exec", "java", "-jar", artifactTargetPath)
    }
  },
  docker / imageNames := Seq(
    ImageName("pennsieve/jobs:latest"),
    ImageName(
      s"pennsieve/jobs:${sys.props.getOrElse("docker-version", version.value)}"
    )
  ),
  assembly / assemblyExcludedJars := {
    val cp = (assembly / fullClasspath).value
    cp filter { _.data.getName == "groovy-2.4.11.jar" }
  },
  assembly / assemblyMergeStrategy := defaultMergeStrategy.value
)

lazy val adminSettings = Seq(
  name := "admin",
  publishTo := publishToNexus.value,
  libraryDependencies ++= Seq(
    "com.pennsieve" %% "discover-service-client" % discoverServiceClientVersion,
    "com.github.swagger-akka-http" %% "swagger-akka-http" % swaggerAkkaHttpVersion.value,
    "com.iheart" %% "ficus" % ficusVersion.value,
    "com.typesafe.akka" %% "akka-http" % akkaHttpVersion.value,
    "io.circe" %% "circe-java8" % circeVersion.value,
    // needed to work correctly on JVM9+ -- this should be moved to bf-akka-http once all bf-akka-http users use JVM9+
    "javax.xml.bind" % "jaxb-api" % "2.2.8",
    // testing deps
    "org.scalatest" %% "scalatest" % scalatestVersion.value % Test,
    "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion.value % Test,
    "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion.value % Test
  ),
  excludeDependencies ++= unwantedDependencies,
  docker / dockerfile := {
    val artifact: File = assembly.value
    val artifactTargetPath = s"/app/${artifact.name}"
    new SecureDockerfile("pennsieve/java-cloudwrap:10-jre-slim-0.5.9") {
      copy(artifact, artifactTargetPath, chown = "pennsieve:pennsieve")
      cmd("--service", "admin", "exec", "java", "-jar", artifactTargetPath)
    }
  },
  docker / imageNames := Seq(ImageName("pennsieve/admin:latest")),
  assembly / assemblyMergeStrategy := defaultMergeStrategy.value
)

lazy val authorizationServiceSettings = Seq(
  name := "authorization-service",
  publishTo := publishToNexus.value,
  libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion.value,
    "com.iheart" %% "ficus" % ficusVersion.value,
    "com.typesafe.akka" %% "akka-http" % akkaHttpVersion.value,
    "io.circe" %% "circe-java8" % circeVersion.value,
    "com.pennsieve" %% "auth-middleware" % authMiddlewareVersion,
    // testing deps
    "org.scalatest" %% "scalatest" % scalatestVersion.value % Test,
    "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion.value % Test,
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion.value % Test,
    "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion.value % Test
  ),
  excludeDependencies ++= unwantedDependencies,
  docker / dockerfile := {
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
  docker / imageNames := Seq(
    ImageName("pennsieve/authorization-service:latest")
  ),
  assembly / assemblyMergeStrategy := defaultMergeStrategy.value
)

lazy val bfAkkaHttpSettings = Seq(
  name := "bf-akka-http",
  publishTo := publishToNexus.value,
  Test / publishArtifact := true,
  publishMavenStyle := true,
  crossScalaVersions := supportedScalaVersions,
  ficusVersion := getVersion(
    scalaVersion.value,
    ficus212Version,
    ficus213Version
  ),
  akkaVersion := getVersion(scalaVersion.value, akka212Version, akka213Version),
  akkaHttpVersion := getVersion(
    scalaVersion.value,
    akkaHttp212Version,
    akkaHttp213Version
  ),
  circeVersion := getVersion(
    scalaVersion.value,
    circe212Version,
    circe213Version
  ),
  swaggerAkkaHttpVersion := getVersion(
    scalaVersion.value,
    swaggerAkkaHttp212Version,
    swaggerAkkaHttp213Version
  ),
  scalatestVersion := scalatest213Version,
  libraryDependencies ++= Seq(
    "com.github.swagger-akka-http" %% "swagger-akka-http" % swaggerAkkaHttpVersion.value,
    "com.iheart" %% "ficus" % ficusVersion.value,
    "com.typesafe.akka" %% "akka-http" % akkaHttpVersion.value,
    "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion.value,
    "com.typesafe.akka" %% "akka-stream-typed" % akkaVersion.value,
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion.value,
    // testing deps
    "org.scalatest" %% "scalatest" % scalatestVersion.value % Test,
    "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion.value % Test
  ),
  libraryDependencies ++= handle212OnlyDependency(
    scalaVersion.value,
    "io.circe" %% "circe-java8" % circeVersion.value
  )
)

lazy val migrationsSettings = Seq(
  name := "migrations",
  publishTo := publishToNexus.value,
  resolvers ++= Seq("Flyway" at "https://flywaydb.org/repo"),
  crossScalaVersions := supportedScalaVersions,
  ficusVersion := getVersion(
    scalaVersion.value,
    ficus212Version,
    ficus213Version
  ),
  libraryDependencies ++= Seq(
    "com.iheart" %% "ficus" % ficusVersion.value,
    "org.flywaydb" % "flyway-core" % flywayVersion,
    "org.postgresql" % "postgresql" % postgresVersion
  ),
  docker / dockerfile := {
    val artifact: File = assembly.value
    val artifactTargetPath = s"/app/${artifact.name}"
    new SecureDockerfile("pennsieve/java-cloudwrap:8-jre-alpine-0.5.9") {
      copy(artifact, artifactTargetPath, chown = "pennsieve:pennsieve")
      // build-postgres.sh script needs a stable JAR name to run without Cloudwrap
      run("ln", "-s", artifactTargetPath, "/app/migrations.jar")
      cmd("--service", "migrations", "exec", "java", "-jar", artifactTargetPath)
    }
  },
  docker / imageNames := Seq(ImageName("pennsieve/migrations:latest")),
  assembly / assemblyMergeStrategy := defaultMergeStrategy.value
)

lazy val unusedOrganizationMigrationSettings = Seq(
  name := "unused-organization-migration",
  libraryDependencies ++= Seq(),
  excludeDependencies ++= unwantedDependencies,
  docker / dockerfile := {
    val artifact: File = assembly.value
    val artifactTargetPath = s"/app/${artifact.name}"
    new SecureDockerfile("pennsieve/java-cloudwrap:8-jre-alpine-0.5.9") {
      copy(artifact, artifactTargetPath, chown = "pennsieve:pennsieve")
      cmd("--service", "migrations", "exec", "java", "-jar", artifactTargetPath)
    }
  },
  docker / imageNames := Seq(
    ImageName("pennsieve/unused-organization-migration:latest")
  ),
  assembly / assemblyMergeStrategy := defaultMergeStrategy.value
)

lazy val inviteCognitoUserSettings = Seq(
  name := "invite-cognito-user",
  libraryDependencies ++= Seq(),
  excludeDependencies ++= unwantedDependencies,
  run / fork := true,
  docker / dockerfile := {
    val artifact: File = assembly.value
    val artifactTargetPath = s"/app/${artifact.name}"
    new SecureDockerfile("pennsieve/java-cloudwrap:8-jre-alpine-0.5.9") {
      copy(artifact, artifactTargetPath, chown = "pennsieve:pennsieve")
      cmd("--service", "admin", "exec", "java", "-jar", artifactTargetPath)
    }
  },
  docker / imageNames := Seq(ImageName("pennsieve/invite-cognito-user:latest")),
  assembly / assemblyMergeStrategy := defaultMergeStrategy.value
)

lazy val etlDataCLISettings = Seq(
  name := "etl-data-cli",
  libraryDependencies ++= Seq(
    "com.github.scopt" %% "scopt" % "3.7.0",
    "io.circe" %% "circe-java8" % circeVersion.value
  ),
  excludeDependencies ++= unwantedDependencies,
  docker / dockerfile := {
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
  docker / imageNames := Seq(ImageName("pennsieve/etl-data-cli:latest")),
  assembly / assemblyMergeStrategy := defaultMergeStrategy.value
)

lazy val uploadsConsumerSettings = Seq(
  name := "uploads-consumer",
  libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-http" % akkaHttpVersion.value,
    "com.typesafe.akka" %% "akka-stream-contrib" % akkaStreamContribVersion,
    "com.dimafeng" %% "testcontainers-scala" % testContainersVersion % Test
  ),
  excludeDependencies ++= unwantedDependencies,
  docker / dockerfile := {
    val artifact: File = assembly.value
    val artifactTargetPath = s"/app/${artifact.name}"
    new SecureDockerfile("pennsieve/openjdk:8-alpine3.9") {
      copy(artifact, artifactTargetPath, chown = "pennsieve:pennsieve")
      cmd("java", "-jar", artifactTargetPath)
    }
  },
  docker / imageNames := Seq(ImageName("pennsieve/uploads-consumer:latest")),
  assembly / assemblyMergeStrategy := defaultMergeStrategy.value
)

lazy val bfAkkaSettings = Seq(
  name := "bf-akka",
  libraryDependencies ++= Seq(
    "com.pennsieve" %% "utilities" % utilitiesVersion,
    "com.typesafe.akka" %% "akka-stream" % akkaVersion.value,
    "com.lightbend.akka" %% "akka-stream-alpakka-sns" % alpakkaVersion,
    "com.lightbend.akka" %% "akka-stream-alpakka-sqs" % alpakkaVersion,
    "com.typesafe.akka" %% "akka-stream-contrib" % akkaStreamContribVersion,
    "io.circe" %% "circe-core" % circeVersion.value,
    "org.typelevel" %% "cats-core" % catsVersion.value
  )
)

lazy val coreClientsSettings = Seq(
  name := "core-clients",
  publishTo := publishToNexus.value,
  publishMavenStyle := true,
  crossScalaVersions := supportedScalaVersions,
  circeVersion := getVersion(
    scalaVersion.value,
    circe212Version,
    circe213Version
  ),
  akkaVersion := getVersion(scalaVersion.value, akka212Version, akka213Version),
  akkaCirceVersion := getVersion(
    scalaVersion.value,
    akkaCirce212Version,
    akkaCirce213Version
  ),
  akkaHttpVersion := getVersion(
    scalaVersion.value,
    akkaHttp212Version,
    akkaHttp213Version
  ),
  libraryDependencies ++= Seq(
    "com.pennsieve" %% "utilities" % utilitiesVersion,
    "de.heikoseeberger" %% "akka-http-circe" % akkaCirceVersion.value,
    "com.typesafe.akka" %% "akka-http" % akkaHttpVersion.value,
    "com.typesafe.akka" %% "akka-stream" % akkaVersion.value,
    "io.circe" %% "circe-core" % circeVersion.value,
    "io.circe" %% "circe-generic" % circeVersion.value,
    "io.circe" %% "circe-generic-extras" % circeVersion.value
  )
)

lazy val coreModelsSettings = Seq(
  name := "core-models",
  publishTo := publishToNexus.value,
  publishMavenStyle := true,
  crossScalaVersions := supportedScalaVersions,
  circeVersion := getVersion(
    scalaVersion.value,
    circe212Version,
    circe213Version
  ),
  enumeratumVersion := getVersion(
    scalaVersion.value,
    enumeratum212Version,
    enumeratum213Version
  ),
  circeDerivationVersion := getVersion(
    scalaVersion.value,
    circeDerivation212Version,
    circeDerivation213Version
  ),
  scalatestVersion := scalatest213Version,
  libraryDependencies ++= Seq(
    "commons-io" % "commons-io" % commonsIoVersion,
    "com.beachape" %% "enumeratum" % enumeratumVersion.value,
    "com.beachape" %% "enumeratum-circe" % enumeratumVersion.value,
    "io.circe" %% "circe-core" % circeVersion.value,
    "io.circe" %% "circe-generic-extras" % circeVersion.value,
    "io.circe" %% "circe-shapes" % circeVersion.value,
    "io.circe" %% "circe-derivation" % circeDerivationVersion.value,
    "io.circe" %% "circe-parser" % circeVersion.value,
    "org.scalatest" %% "scalatest" % scalatestVersion.value % Test,
    "com.google.guava" % "guava" % "29.0-jre"
  ),
  libraryDependencies ++= handle212OnlyDependency(
    scalaVersion.value,
    "io.circe" %% "circe-java8" % circeVersion.value
  )
)

lazy val bfAwsSettings = Seq(
  name := "bf-aws",
  publishTo := publishToNexus.value,
  publishMavenStyle := true,
  crossScalaVersions := supportedScalaVersions,
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
    "com.typesafe.akka" %% "akka-http" % akkaHttpVersion.value,
    "com.typesafe.akka" %% "akka-http-xml" % akkaHttpVersion.value,
    "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion.value,
    "com.typesafe.akka" %% "akka-stream-typed" % akkaVersion.value,
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion.value,
    "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion.value % Test
  ),
  excludeDependencies ++= unwantedDependencies,
  docker / dockerfile := {
    val artifact: File = assembly.value
    val artifactTargetPath = s"/app/${artifact.name}"
    new SecureDockerfile("pennsieve/openjdk:8-alpine3.9") {
      copy(artifact, artifactTargetPath, chown = "pennsieve:pennsieve")
      cmd("java", "-jar", artifactTargetPath)
    }
  },
  docker / imageNames := Seq(ImageName("pennsieve/discover-publish:latest")),
  assembly / assemblyMergeStrategy := defaultMergeStrategy.value
)

lazy val organizationStorageMigrationSettings =
  Seq(
    name := "organization-storage-migration",
    excludeDependencies ++= unwantedDependencies,
    docker / dockerfile := {
      val artifact: File = assembly.value
      val artifactTargetPath = s"/app/${artifact.name}"
      new SecureDockerfile("pennsieve/java-cloudwrap:8-jre-alpine-0.5.9") {
        copy(artifact, artifactTargetPath, chown = "pennsieve:pennsieve")
        cmd("--service", "admin", "exec", "java", "-jar", artifactTargetPath)
      }
    },
    docker / imageNames := Seq(
      ImageName("pennsieve/organization-storage-migration:latest")
    ),
    assembly / assemblyMergeStrategy := defaultMergeStrategy.value
  )

// Generates Scala template strings from raw HTML files.
// See project/CompileMessageTemplates.scala for plugin implementation.
lazy val messageTemplateSettings = Seq(
  name := "message-templates",
  publishTo := publishToNexus.value,
  crossScalaVersions := supportedScalaVersions,
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
  .dependsOn(
    `core-models`,
    `bf-aws`,
    `message-templates`,
    migrations % "test->compile"
  )
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

lazy val `invite-cognito-user` = project
  .enablePlugins(sbtdocker.DockerPlugin)
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings: _*)
  .settings(inviteCognitoUserSettings: _*)
  .dependsOn(core % "test->test;compile->compile")

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
    `message-templates`,
    `invite-cognito-user`
  )
  .settings(commonSettings: _*)
  .settings( // crossScalaVersions must be set to Nil on the aggregating project
    crossScalaVersions := Nil,
    publish / skip := true
  )
