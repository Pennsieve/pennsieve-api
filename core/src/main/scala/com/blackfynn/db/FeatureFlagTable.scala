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

package com.pennsieve.db

import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.models.{ Feature, FeatureFlag }
import java.time.ZonedDateTime

import slick.dbio.Effect
import slick.sql.FixedSqlStreamingAction

final class FeatureFlagsTable(tag: Tag)
    extends Table[FeatureFlag](tag, Some("pennsieve"), "feature_flags") {

  def organizationId = column[Int]("organization_id")
  def feature = column[Feature]("feature")
  def enabled = column[Boolean]("enabled")
  def createdAt =
    column[ZonedDateTime]("created_at", O.AutoInc) // set by the database on insert
  def updatedAt =
    column[ZonedDateTime]("updated_at", O.AutoInc) // set by the database on update

  def pk = primaryKey("combined_pk", (organizationId, feature))

  def * =
    (organizationId, feature, enabled, createdAt, updatedAt).mapTo[FeatureFlag]
}

object FeatureFlagsMapper extends TableQuery(new FeatureFlagsTable(_)) {
  def hasFeatureFlagEnabled(
    organizationId: Int,
    feature: Feature
  ): Rep[Boolean] =
    this
      .filter(_.organizationId === organizationId)
      .filter(_.enabled === true)
      .filter(_.feature === feature)
      .exists

  def getActiveFeatureFlags(
    organizationId: Int
  ): Query[FeatureFlagsTable, FeatureFlag, Seq] =
    this
      .filter(_.organizationId === organizationId)
      .filter(_.enabled === true)

  def getActiveFeatures(
    organizationId: Int
  ): Query[Rep[Feature], Feature, Seq] =
    this
      .filter(_.organizationId === organizationId)
      .filter(_.enabled === true)
      .map(_.feature)
}
