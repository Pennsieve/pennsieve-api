/*
 * Copyright 2021 University of Pennsylvania
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pennsieve.clients

import cats.implicits._
import com.amazonaws.services.s3.model.{
  GeneratePresignedUrlRequest,
  ObjectMetadata,
  PutObjectRequest,
  PutObjectResult
}
import com.pennsieve.aws.s3.S3Trait
import com.pennsieve.models.DatasetAsset
import org.apache.commons.io.IOUtils
import org.joda.time.DateTime

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URL
import scala.concurrent.duration.Duration
import com.amazonaws.services.s3.model.SSEAwsKeyManagementParams

trait DatasetAssetClient {

  val bucket: String

  def uploadAsset(
    asset: DatasetAsset,
    size: Long,
    contentType: Option[String],
    inputStream: InputStream
  ): Either[Throwable, PutObjectResult]

  def deleteAsset(asset: DatasetAsset): Either[Throwable, Unit]

  def downloadAsset(asset: DatasetAsset): Either[Throwable, String]

  def generatePresignedUrl(
    asset: DatasetAsset,
    duration: Duration
  ): Either[Throwable, URL]
}

class S3DatasetAssetClient(s3: S3Trait, val bucket: String)
    extends DatasetAssetClient {

  def uploadAsset(
    asset: DatasetAsset,
    size: Long,
    contentType: Option[String],
    inputStream: InputStream
  ): Either[Throwable, PutObjectResult] = {

    val metadata: ObjectMetadata = new ObjectMetadata()
    metadata.setContentLength(size)
    metadata.setContentType(contentType.getOrElse("application/octet-stream"))
    metadata.setSSEAlgorithm(ObjectMetadata.AES_256_SERVER_SIDE_ENCRYPTION)

    val putRequest: PutObjectRequest =
      new PutObjectRequest(asset.s3Bucket, asset.s3Key, inputStream, metadata)

    s3.putObject(putRequest)
  }

  def deleteAsset(asset: DatasetAsset): Either[Throwable, Unit] = {
    s3.deleteObject(asset.s3Bucket, asset.s3Key)
  }

  def downloadAsset(asset: DatasetAsset): Either[Throwable, String] =
    s3.getObject(asset.s3Bucket, asset.s3Key)
      .flatMap(s3Object => {
        val inputStream = s3Object.getObjectContent()
        Either.catchNonFatal(try {
          IOUtils.toString(inputStream, "utf-8")
        } finally {
          inputStream.close()
        })
      })

  def generatePresignedUrl(
    asset: DatasetAsset,
    duration: Duration
  ): Either[Throwable, URL] = {
    val request = new GeneratePresignedUrlRequest(asset.s3Bucket, asset.s3Key)
    request.setExpiration(
      DateTime.now.plusSeconds(duration.toSeconds.toInt).toDate()
    )

    s3.generatePresignedUrl(request)
  }
}
