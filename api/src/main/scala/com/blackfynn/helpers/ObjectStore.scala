// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

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

import collection.JavaConverters._
import scala.annotation.tailrec

/**
  * Created by natevecc on 11/13/16.
  */
trait ObjectStore {

  def getPresignedUrl(
    bucket: String,
    key: String,
    duration: Date
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
    duration: Date
  ): Either[ActionResult, URL] =
    s3Client
      .generatePresignedUrl(
        new GeneratePresignedUrlRequest(bucket, key)
          .withExpiration(duration)
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
