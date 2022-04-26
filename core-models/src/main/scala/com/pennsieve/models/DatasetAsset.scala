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

package com.pennsieve.models

import java.time.ZonedDateTime
import java.util.UUID

import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

final case class DatasetAsset(
  name: String,
  s3Bucket: String,
  s3Key: String,
  datasetId: Int,
  createdAt: ZonedDateTime = ZonedDateTime.now(),
  updatedAt: ZonedDateTime = ZonedDateTime.now(),
  id: UUID = UUID.randomUUID()
) {

  def etag: ETag = ETag(updatedAt)

  def s3Url: String = s"s3://$s3Bucket/$s3Key"
}

object DatasetAsset {
  implicit val decoder: Decoder[DatasetAsset] =
    deriveDecoder[DatasetAsset]
  implicit val encoder: Encoder[DatasetAsset] =
    deriveEncoder[DatasetAsset]

  /*
   * This is required by slick when using a companion object on a case
   * class that defines a database table
   */
  val tupled = (this.apply _).tupled
}
