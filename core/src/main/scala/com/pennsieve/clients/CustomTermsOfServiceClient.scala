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

import com.pennsieve.aws.s3.S3Trait
import com.amazonaws.services.s3.model.{ PutObjectResult, S3Object }
import cats.implicits._
import com.amazonaws.services.s3.model.ObjectMetadata
import com.pennsieve.models.{ DateVersion, ToDateVersion }
import com.typesafe.config.Config
import java.io.ByteArrayInputStream
import net.ceedubs.ficus.Ficus._
import java.time.{ ZoneOffset, ZonedDateTime }

import scala.io.Source

trait CustomTermsOfServiceClient {

  def getTermsOfService[V](
    organizationId: String,
    version: V
  )(implicit
    tdv: ToDateVersion[V]
  ): Either[Throwable, String]

  def updateTermsOfService[V](
    organizationId: String,
    text: String,
    version: V = ZonedDateTime.now(ZoneOffset.UTC)
  )(implicit
    tdv: ToDateVersion[V]
  ): Either[Throwable, (DateVersion, ETag, MD5)]
}

final case class ETag(value: String) extends AnyVal {
  override def toString: String = value
}

final case class MD5(value: String) extends AnyVal {
  override def toString: String = value
}

/**
  * A client that stores custom terms of use documents for an organization on S3.
  *
  * Documents are stored on S3 according to the convention:
  *
  * - `s3://${ASSET_BUCKET}/${TERMS_DOCUMENTS_PREFIX}/${organizationId}/terms_of_use_${version}.html`
  *
  * @param s3
  */
class S3CustomTermsOfServiceClient(s3: S3Trait, termsOfServiceBucket: String)
    extends CustomTermsOfServiceClient {

  private val CUSTOM_TERMS_OF_SERVICE_PREFIX = "custom"

  private def tosFilePath[V](
    organizationId: String,
    version: V
  )(implicit
    tdv: ToDateVersion[V]
  ): String =
    s"${CUSTOM_TERMS_OF_SERVICE_PREFIX}/${organizationId}/terms_of_use_${tdv.toDateVersion(version)}.html"

  private def readContents(obj: S3Object): Either[Throwable, String] =
    Either.catchNonFatal(Source.fromInputStream(obj.getObjectContent).mkString)

  /**
    * Returns the HTML contents of the terms of service at a specific version.
    *
    * @param organizationId
    * @return
    */
  def getTermsOfService[V](
    organizationId: String,
    version: V
  )(implicit
    tdv: ToDateVersion[V]
  ): Either[Throwable, String] =
    for {
      tosHtml <- s3
        .getObject(termsOfServiceBucket, tosFilePath(organizationId, version))
      contents <- readContents(tosHtml)
    } yield contents

  /**
    * Sets the HTML contents of the terms of service, returning the result of the S3 operation
    * and the provided terms of service version.
    *
    * @param organizationId
    * @param version
    * @param text
    * @return
    */
  def updateTermsOfService[V](
    organizationId: String,
    text: String,
    version: V = ZonedDateTime.now(ZoneOffset.UTC)
  )(implicit
    tdv: ToDateVersion[V]
  ): Either[Throwable, (DateVersion, ETag, MD5)] = {
    val dv = tdv.toDateVersion(version)
    val stream = new ByteArrayInputStream(text.getBytes)
    for {
      result <- s3.putObject(
        termsOfServiceBucket,
        tosFilePath(organizationId, dv),
        stream,
        new ObjectMetadata
      )
      _ = stream.close()
    } yield (dv, ETag(result.getETag), MD5(result.getContentMd5))
  }
}

trait CustomTermsOfServiceClientContainer {
  val config: Config
  val s3: S3Trait

  val customTermsOfServiceClient: CustomTermsOfServiceClient
}

trait S3CustomTermsOfServiceClientContainer
    extends CustomTermsOfServiceClientContainer {
  val config: Config
  val s3: S3Trait
  val customTermsOfServiceClient = new S3CustomTermsOfServiceClient(
    s3,
    config.as[String]("pennsieve.s3.terms_of_service_bucket_name")
  )
}
