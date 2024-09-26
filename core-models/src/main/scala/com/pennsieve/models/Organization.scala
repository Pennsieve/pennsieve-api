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
import io.circe.{ Decoder, Encoder }
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto._
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

case class OrganizationNodeId(val value: String) extends AnyVal

final case class Organization(
  nodeId: String,
  name: String,
  slug: String,
  terms: Option[String] = None,
  encryptionKeyId: Option[String] = None,
  customTermsOfServiceVersion: Option[ZonedDateTime] = None,
  /*
   * Optional name of an S3 storage bucket for organizations that want data to
   * somewhere other than the default Pennsieve bucket. At the moment this is
   * only used by SPARC.
   */
  storageBucket: Option[String] = None,
  publishBucket: Option[String] = None,
  embargoBucket: Option[String] = None,
  colorTheme: Option[(String, String)] = None,
  bannerImageURI: Option[String] = None,
  createdAt: ZonedDateTime = ZonedDateTime.now(),
  updatedAt: ZonedDateTime = ZonedDateTime.now(),
  id: Int = 0
) {

  def typedNodeId: OrganizationNodeId = OrganizationNodeId(nodeId)

  def schemaId: String = id.toString
}

object Organization {
  implicit val encoder: Encoder[Organization] = deriveEncoder[Organization]
  implicit val decoder: Decoder[Organization] = deriveDecoder[Organization]

  /*
   * This is required by slick when using a companion object on a case
   * class that defines a database table
   */
  val tupled = (this.apply _).tupled

  type ColorTheme = (String, String)
  implicit val customConfig: Configuration =
    Configuration.default.withSnakeCaseMemberNames.withDefaults
  implicit val snakyEncoder: Encoder[ColorTheme] =
    deriveConfiguredEncoder
  implicit val snakyDecoder: Decoder[ColorTheme] =
    deriveConfiguredDecoder
}
