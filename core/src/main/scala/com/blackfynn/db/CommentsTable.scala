// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

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
