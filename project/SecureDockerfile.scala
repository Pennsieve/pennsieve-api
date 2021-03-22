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
    "https://s3.amazonaws.com/rds-downloads/rds-ca-2019-root.pem"
  )
}
