// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.db

import com.blackfynn.traits.PostgresProfile.api._
import com.blackfynn.models.{
  Annotation,
  ModelProperty,
  Organization,
  PathElement
}
import java.time.ZonedDateTime

final class AnnotationsTable(schema: String, tag: Tag)
    extends Table[Annotation](tag, Some(schema), "annotations") {

  // set by the database
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)
  def updatedAt = column[ZonedDateTime]("updated_at", O.AutoInc)

  def layerId = column[Int]("layer_id")
  def creatorId = column[Int]("creator_id")
  def description = column[String]("description")
  def attributes = column[List[ModelProperty]]("attributes")
  def path = column[List[PathElement]]("path")

  def * =
    (
      creatorId,
      layerId,
      description,
      path,
      attributes,
      createdAt,
      updatedAt,
      id
    ).mapTo[Annotation]
}

class AnnotationsMapper(val organization: Organization)
    extends TableQuery(new AnnotationsTable(organization.schemaId, _))
