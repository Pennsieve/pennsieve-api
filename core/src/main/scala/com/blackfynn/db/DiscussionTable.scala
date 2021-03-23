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
import com.pennsieve.models.{ Discussion, Organization }
import java.time.ZonedDateTime

final class DiscussionTable(schema: String, tag: Tag)
    extends Table[Discussion](tag, Some(schema), "discussions") {

  // set by the database
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)
  def updatedAt = column[ZonedDateTime]("updated_at", O.AutoInc)

  def packageId = column[Int]("package_id")
  def annotationId = column[Option[Int]]("annotation_id")
  def timeSeriesAnnotationId = column[Option[Int]]("ts_annotation_id")

  def * =
    (packageId, annotationId, timeSeriesAnnotationId, createdAt, updatedAt, id)
      .mapTo[Discussion]
}

class DiscussionsMapper(val organization: Organization)
    extends TableQuery(new DiscussionTable(organization.schemaId, _)) {

  def update(discussion: Discussion) =
    this.filter(_.id === discussion.id).update(discussion)

}
