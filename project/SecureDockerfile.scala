import sbtdocker.Dockerfile

/** A dockerfile that contains the AWS root cert in the place where
  * postgres expects to find it (~/.postgresql/root.crt).
  */
abstract class SecureDockerfile(image: String) extends Dockerfile {
  from(image)
  run("mkdir", "-p", "/home/pennsieve/.postgresql")
  run(
    "wget",
    "-qO",
    "/home/pennsieve/.postgresql/root.crt",
    "https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem"
  )
}
