// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

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
