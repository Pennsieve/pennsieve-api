// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.models

import java.time.ZonedDateTime
import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.java8.time._

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
}
