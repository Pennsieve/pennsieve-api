package com.pennsieve.db

import com.pennsieve.models._
import com.pennsieve.traits.PostgresProfile.api._

import scala.concurrent.ExecutionContext

final class OrganizationStorageTable(tag: Tag)
    extends Table[OrganizationStorage](
      tag,
      Some("pennsieve"),
      "organization_storage"
    ) {

  def organizationId = column[Int]("organization_id", O.PrimaryKey)
  def size = column[Option[Long]]("size")

  def * = (organizationId, size).mapTo[OrganizationStorage]
}

object OrganizationStorageMapper
    extends TableQuery(new OrganizationStorageTable(_)) {

  def incrementOrganization(
    organizationId: Int,
    size: Long
  )(implicit
    ec: ExecutionContext
  ): DBIO[Int] =
    sql"""
       INSERT INTO pennsieve.organization_storage
       AS organization_storage (organization_id, size)
       VALUES ($organizationId, $size)
       ON CONFLICT (organization_id)
       DO UPDATE SET size = COALESCE(organization_storage.size, 0) + EXCLUDED.size
      """
      .as[Int]
      .map(_.headOption.getOrElse(0))

  def setOrganization(
    organizationId: Int,
    size: Long
  )(implicit
    ec: ExecutionContext
  ): DBIO[Int] =
    sql"""
       INSERT INTO pennsieve.organization_storage (organization_id, size)
       VALUES ($organizationId, $size)
       ON CONFLICT (organization_id)
       DO UPDATE SET size = EXCLUDED.size
       """
      .as[Int]
      .map(_.headOption.getOrElse(0))
}
