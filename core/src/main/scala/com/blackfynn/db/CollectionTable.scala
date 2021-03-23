// Copyright (c) 2020 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.db

import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.models.{ Collection, Organization }
import java.time.ZonedDateTime

final class CollectionTable(schema: String, tag: Tag)
    extends Table[Collection](tag, Some(schema), "collections") {

  // set by the database
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def name = column[String]("name")
  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)
  def updatedAt = column[ZonedDateTime]("updated_at", O.AutoInc)

  def * =
    (name, createdAt, updatedAt, id).mapTo[Collection]
}

class CollectionMapper(val organization: Organization)
    extends TableQuery(new CollectionTable(organization.schemaId, _)) {

  def get(id: Int) =
    this
      .filter(_.id === id)
}
