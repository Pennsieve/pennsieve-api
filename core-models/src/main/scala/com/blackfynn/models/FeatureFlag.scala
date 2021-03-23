// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.models

import enumeratum._
import enumeratum.EnumEntry._
import java.time.ZonedDateTime
import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.java8.time._

import scala.collection.immutable

final case class FeatureFlag(
  organizationId: Int,
  feature: Feature,
  enabled: Boolean = true,
  createdAt: ZonedDateTime = ZonedDateTime.now(),
  updatedAt: ZonedDateTime = ZonedDateTime.now()
)

object FeatureFlag {
  implicit val encoder: Encoder[FeatureFlag] = deriveEncoder[FeatureFlag]
  implicit val decoder: Decoder[FeatureFlag] = deriveDecoder[FeatureFlag]

  /*
   * This is required by slick when using a companion object on a case
   * class that defines a database table
   */
  val tupled = (this.apply _).tupled
}

sealed trait Feature extends EnumEntry with Snakecase

object Feature extends Enum[Feature] with CirceEnum[Feature] {
  val values: immutable.IndexedSeq[Feature] = findValues

  case object TimeSeriesEventsFeature extends Feature
  case object Viewer2Feature extends Feature
  case object ConceptsFeature extends Feature
  case object DiscoverFeature extends Feature
  case object OldETL extends Feature
  case object NewETL extends Feature
  case object ETLFairness extends Feature
  case object ClinicalManagementFeature extends Feature
  case object ModelTemplatesFeature extends Feature
  case object DatasetTemplatesFeature extends Feature
  case object Uploads2Feature extends Feature
  case object ProgressionToolFeature extends Feature
  case object Discover2Feature extends Feature
  case object DoiFeature extends Feature
}
