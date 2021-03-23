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
import com.pennsieve.models.{ Comment, Organization }
import java.time.ZonedDateTime

final class CommentsTable(schema: String, tag: Tag)
    extends Table[Comment](tag, Some(schema), "comments") {

  // set by the database
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)
  def updatedAt = column[ZonedDateTime]("updated_at", O.AutoInc)

  def creatorId = column[Int]("creator_id")
  def discussionId = column[Int]("discussion_id")
  def message = column[String]("message")

  def * =
    (discussionId, creatorId, message, createdAt, updatedAt, id).mapTo[Comment]
}

class CommentsMapper(val organization: Organization)
    extends TableQuery(new CommentsTable(organization.schemaId, _))
