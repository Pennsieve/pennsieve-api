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
import com.pennsieve.aws.s3.{S3, S3ClientFactory}
import com.amazonaws.services.s3.model._
import cats.syntax.either._
import com.amazonaws.services.s3.AmazonS3

import java.net.URL
import org.scalatra.{ActionResult, InternalServerError, NotFound}

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

  def getMD5(bucket: String, key: String): Either[ActionResult, String] =
    s3Client
      .getObjectMetadata(bucket, key)
      .map(_.getContentMD5)
      .leftMap(t => InternalServerError(t.getMessage))

  def getPresignedUrl(
    bucket: String,
    key: String,
    duration: Date,
    fileName: String
  ): Either[ActionResult, URL] = {
    val region = S3ClientFactory.getRegionFromBucket(bucket)

    if (region == "us-east-1") {
      generateUrl(s3Client.client, bucket, key, duration, fileName)
    } else {
      // New logic for non-us-east-1 regions
      val regionalS3Client = S3ClientFactory.getClientForRegion(region)
      generateUrl(regionalS3Client, bucket, key, duration, fileName)
    }
  }

  private def generateUrl(
    client: AmazonS3,
    bucket: String,
    key: String,
    duration: Date,
    fileName: String
  ): Either[ActionResult, URL] = {

    Either
      .catchNonFatal {
        client.generatePresignedUrl(
          new GeneratePresignedUrlRequest(bucket, key)
            .withExpiration(duration)
            .withResponseHeaders(
              new ResponseHeaderOverrides()
                .withContentDisposition(s"""attachment; filename="$fileName"""")
            )
        )
      }
      .leftMap(t => InternalServerError(t.getMessage))
  }

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
