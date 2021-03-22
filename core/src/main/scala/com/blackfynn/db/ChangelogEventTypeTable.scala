// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.db

import com.blackfynn.domain._
import com.blackfynn.models._
import com.blackfynn.traits.PostgresProfile.api._
import java.time.ZonedDateTime

import cats.Semigroup
import cats.implicits._
import com.rms.miu.slickcats.DBIOInstances._
import io.circe.Json
import io.circe.syntax._
import io.circe.parser.decode
import slick.lifted.Case._

import com.blackfynn.domain.SqlError
import com.blackfynn.traits.PostgresProfile

import scala.concurrent.ExecutionContext

final class ChangelogEventTypeTable(schema: String, tag: Tag)
    extends Table[ChangelogEventType](
      tag,
      Some(schema),
      "changelog_event_types"
    ) {

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def name = column[ChangelogEventName]("name")
  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)

  def * =
    (name, createdAt, id).mapTo[ChangelogEventType]
}

class ChangelogEventTypeMapper(val organization: Organization)
    extends TableQuery(new ChangelogEventTypeTable(organization.schemaId, _)) {

  def getOrCreate(
    name: ChangelogEventName
  )(implicit
    ec: ExecutionContext
  ): DBIO[ChangelogEventType] =
    sql"""
       WITH created AS (
         INSERT INTO "#${organization.schemaId}".changelog_event_types
         AS event_types (name)
         VALUES (${name.entryName})
         ON CONFLICT (name) DO NOTHING
         RETURNING id, created_at
      )
      SELECT id, created_at
      FROM created

      UNION ALL

      SELECT id, created_at
      FROM "#${organization.schemaId}".changelog_event_types
      WHERE name = ${name.entryName}
       """
      .as[(Int, ZonedDateTime)]
      .headOption
      .flatMap {
        case Some((id, createdAt)) =>
          DBIO.successful(ChangelogEventType(name, createdAt, id))
        case None =>
          DBIO.failed(NotFound(s"Event type ${name.entryName}"))
      }

}
