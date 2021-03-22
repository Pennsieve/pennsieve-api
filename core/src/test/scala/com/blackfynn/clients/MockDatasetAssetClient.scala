package com.blackfynn.clients

import com.amazonaws.services.s3.model.{ ObjectMetadata, PutObjectResult }
import com.blackfynn.models.DatasetAsset
import org.apache.commons.io.IOUtils

import java.io.InputStream
import java.net.URL
import java.util.UUID
import scala.collection.mutable
import scala.concurrent.duration.Duration

class MockDatasetAssetClient extends DatasetAssetClient {

  val assets: mutable.Map[UUID, (String, ObjectMetadata)] =
    mutable.Map.empty

  val bucket: String = "test-dataset-asset-bucket"

  def uploadAsset(
    asset: DatasetAsset,
    size: Long,
    contentType: Option[String],
    inputStream: InputStream
  ): Either[Throwable, PutObjectResult] = {
    val content = IOUtils.toString(inputStream, "utf-8")

    val metadata: ObjectMetadata = new ObjectMetadata()
    metadata.setContentLength(size)
    metadata.setContentType(contentType.getOrElse("application/octet-stream"))
    metadata.setSSEAlgorithm(ObjectMetadata.AES_256_SERVER_SIDE_ENCRYPTION)

    val result = new PutObjectResult()
    result.setMetadata(metadata)

    assets += asset.id -> (content, metadata)

    Right(result)
  }

  def deleteAsset(asset: DatasetAsset): Either[Throwable, Unit] = {
    assets -= asset.id
    Right(())
  }

  def downloadAsset(asset: DatasetAsset): Either[Throwable, String] =
    assets.get(asset.id) match {
      case Some((content, _)) => Right(content)
      case None => Left(new Exception(s"asset ${asset.id} not found"))
    }

  def generatePresignedUrl(
    asset: DatasetAsset,
    duration: Duration
  ): Either[Throwable, URL] =
    Right(
      new URL(
        s"https://${asset.s3Bucket}.s3.amazonaws.com/${asset.s3Key}?presigned=true"
      )
    )
}
