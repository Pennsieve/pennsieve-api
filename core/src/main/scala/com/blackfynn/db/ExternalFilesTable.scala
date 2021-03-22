// Copyright (c) 2019 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.db

import java.time.ZonedDateTime

import com.blackfynn.models.{ ExternalFile, Organization, Package }
import com.blackfynn.traits.PostgresProfile.api._

final class ExternalFilesTable(schema: String, tag: Tag)
    extends Table[ExternalFile](tag, Some(schema), "externally_linked_files") {

  def packageId = column[Int]("package_id", O.PrimaryKey)
  def description = column[Option[String]]("description")
  def location = column[String]("location")
  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)
  def updatedAt = column[ZonedDateTime]("updated_at", O.AutoInc)

  def * =
    (packageId, location, description, createdAt, updatedAt)
      .mapTo[ExternalFile]
}

class ExternalFilesMapper(val organization: Organization)
    extends TableQuery(new ExternalFilesTable(organization.schemaId, _)) {

  def get(`package`: Package) =
    this.filter(_.packageId === `package`.id)

  def get(packages: Seq[Package]) =
    this.filter(_.packageId inSetBind packages.map(_.id))
}
