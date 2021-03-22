// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.db

import com.blackfynn.traits.PostgresProfile.api._
import com.blackfynn.models.{ AnnotationLayer, Organization }
import java.time.ZonedDateTime

final class AnnotationLayersTable(schema: String, tag: Tag)
    extends Table[AnnotationLayer](tag, Some(schema), "annotation_layers") {

  // set by the database
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)
  def updatedAt = column[ZonedDateTime]("updated_at", O.AutoInc)

  def name = column[String]("name")
  def packageId = column[Int]("package_id")
  def color = column[String]("color")

  def * =
    (name, packageId, color, createdAt, updatedAt, id).mapTo[AnnotationLayer]
}

class AnnotationLayersMapper(val organization: Organization)
    extends TableQuery(new AnnotationLayersTable(organization.schemaId, _))
