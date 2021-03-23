package com.pennsieve.clients

import akka.stream.ActorMaterializer
import com.pennsieve.utilities.Container

trait MockUploadServiceContainer extends UploadServiceContainer {
  self: Container =>

  lazy val uploadServiceConfigPath = "upload_service"

  import net.ceedubs.ficus.Ficus._
  override lazy val materializer: ActorMaterializer =
    ActorMaterializer()
  lazy val uploadServiceHost: String =
    config.as[String](s"$uploadServiceConfigPath.host")

  override val uploadServiceClient: UploadServiceClient =
    new LocalUploadServiceClient()(system, materializer, ec)
}
