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

package com.pennsieve.helpers

import com.pennsieve.api.Error
import com.pennsieve.helpers.either.EitherErrorHandler.implicits._
import com.pennsieve.web.Settings

import com.pennsieve.aws.s3.S3
import com.amazonaws.services.s3.model._
import cats.syntax.either._
import java.net.URL
import org.scalatra.{ ActionResult, InternalServerError, NotFound }
import scala.util.Try
import scala.concurrent.duration._
import java.util.Date

import scala.annotation.tailrec

/**
  * Created by natevecc on 11/13/16.
  */
trait ObjectStore {

  def getPresignedUrl(
    bucket: String,
    key: String,
    duration: Date,
    fileName: String
  ): Either[ActionResult, URL]

  def getListing(
    bucket: String,
    prefix: String
  ): Either[ActionResult, Map[String, Long]]

  def getMD5(bucket: String, key: String): Either[ActionResult, String]

}

class S3ObjectStore(s3Client: S3) extends ObjectStore {

  // private val regionalClientsMap = new TrieMap[String, AmazonS3]()

  def getMD5(bucket: String, key: String): Either[ActionResult, String] =
    s3Client
      .getObjectMetadata(bucket, key)
      .map(_.getContentMD5)
      .leftMap(t => InternalServerError(t.getMessage))

//  private def createS3Client(region: String): AmazonS3 = {
//     AmazonS3ClientBuilder.standard()
//       .withRegion(region)
//       .build()
//   }

//   private def getOrCreateClient(region: String): AmazonS3 = {
//     if (region == "us-east-1") s3Client
//     else regionalClientsMap.getOrElseUpdate(region, {
//       var regionalClientConfig = new ClientConfiguration().withSignerOverride("AWSS3V4SignerType")
//       AmazonS3ClientBuilder.standard()
//         .withClientConfiguration(regionalClientConfig)
//         .withCredentials(DefaultAWSCredentialsProviderChain.getInstance())
//         .withRegion(region)
//         .build()
//     })
//   }

//   def getPresignedUrl(
//     bucket: String,
//     key: String,
//     duration: Date,
//     fileName: String,
//     region: String
//   ): Either[ActionResult, URL] = {

//     val bucketRegion = s3Client.getBucketLocation(bucketName)
//     val s3ClientForRegion = getOrCreateClient(bucketRegion)

//     Either.catchNonFatal(
//       s3ClientForRegion.generatePresignedUrl(
//         new GeneratePresignedUrlRequest(bucket, key)
//           .withExpiration(duration)
//           .withResponseHeaders(
//             new ResponseHeaderOverrides()
//               .withContentDisposition(s"""attachment; filename="$fileName"""")
//           )
//       )
//     ).leftMap(t => InternalServerError(t.getMessage))
//   }
// }

  def getRegionFromBucket(bucket: String): String = {
    val regionMappings: Map[String, String] = Map(
      "afs-1"  -> "af-south-1",
      "use-1"  -> "us-east-1",
      "use-2"  -> "us-east-2",
      "usw-1"  -> "us-west-1",
      "usw-2"  -> "us-west-2",
      // Add more regions
    )
    regionMappings
      .collectFirst { case (suffix, region) if bucket.endsWith(suffix) => region }
      .getOrElse("us-east-1")
  }

  def getPresignedUrl(
    bucket: String,
    key: String,
    duration: Date,
    fileName: String
  ): Either[ActionResult, URL] =
    
    val region = getRegionFromBucket(bucket)
    s3Client
      .generatePresignedUrl(
        new GeneratePresignedUrlRequest(bucket, key)
          .withExpiration(duration)
          .withResponseHeaders(
            new ResponseHeaderOverrides()
              .withContentDisposition(s"""attachment; filename="$fileName"""")
          ),
          region
      )
      .leftMap(t => InternalServerError(t.getMessage))

  def getListing(
    bucket: String,
    prefix: String
  ): Either[ActionResult, Map[String, Long]] = {
    s3Client
      .objectSummaries(bucket, prefix)
      .map(_.map(obj => (obj.getKey, obj.getSize)).toMap)
      .leftMap(t => InternalServerError(t.getMessage))

  }
}
