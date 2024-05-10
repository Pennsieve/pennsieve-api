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
