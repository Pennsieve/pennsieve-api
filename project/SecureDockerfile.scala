import sbtdocker.Dockerfile

// A Dockerfile that contains the AWS RDS CA root certificate
// Assumes the current user is pennsieve
abstract class SecureDockerfile(image: String) extends Dockerfile {
  // Where Postgres (psql/JDBC) expects to find the trusted CA certificate
  val CA_CERT_LOCATION = "/home/pennsieve/.postgresql/root.crt"

  from(image)
  addRaw(
    "https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem",
    CA_CERT_LOCATION,
  )
  user("root")
  run("chmod", "+r", CA_CERT_LOCATION)
  user("pennsieve")
}

object SecureDockerfile {
  // Built from Pennsieve/docker-base-images java-cloudwrap/21 and tagged with
  // the commit the base-image job built.
  val BaseImageTag = "035617f"
  val JavaImage = s"pennsieve/java-cloudwrap:21-jre-alpine-$BaseImageTag"
  val TomcatImage = s"pennsieve/tomcat-cloudwrap:9-jre21-alpine-$BaseImageTag"
}
