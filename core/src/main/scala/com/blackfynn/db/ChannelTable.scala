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
import com.pennsieve.models.{ ModelProperty, Organization }
import java.time.ZonedDateTime

import com.pennsieve.domain.SqlError
import com.pennsieve.models.Channel

import scala.concurrent.ExecutionContext

final class ChannelTable(schema: String, tag: Tag)
    extends Table[Channel](tag, Some(schema), "channels") {

  // set by the database
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)

  def nodeId = column[String]("node_id")
  def packageId = column[Int]("package_id")
  def name = column[String]("name")
  def start = column[Long]("start")
  def end = column[Long]("end")
  def unit = column[String]("unit")
  def rate = column[Double]("rate")
  def `type` = column[String]("type")
  def group = column[Option[String]]("group")
  def lastAnnotation = column[Long]("last_annotation")
  def spikeDuration = column[Option[Long]]("spike_duration")
  def properties = column[List[ModelProperty]]("properties")

  def * =
    (
      nodeId,
      packageId,
      name,
      start,
      end,
      unit,
      rate,
      `type`,
      group,
      lastAnnotation,
      spikeDuration,
      properties,
      createdAt,
      id
    ).mapTo[Channel]
}

class ChannelsMapper(val organization: Organization)
    extends TableQuery(new ChannelTable(organization.schemaId, _)) {
  def get(id: Int) = this.filter(_.id === id)
  def getByNodeId(nodeId: String) = this.filter(_.nodeId === nodeId)
  def getByPackageId(packageId: Int) = this.filter(_.packageId === packageId)
  def getByPackageIdAndName(packageId: Int, name: String) =
    this.filter(_.packageId === packageId).filter(_.name === name)

  def getChannel(
    id: Int
  )(implicit
    executionContext: ExecutionContext
  ): DBIO[Channel] = {
    this
      .get(id)
      .result
      .headOption
      .flatMap {
        case None => DBIO.failed(SqlError(s"No channel with id ($id) exists"))
        case Some(channel) => DBIO.successful(channel)
      }
  }

  def getChannelByNodeId(
    nodeId: String
  )(implicit
    executionContext: ExecutionContext
  ): DBIO[Channel] = {
    this
      .getByNodeId(nodeId)
      .result
      .headOption
      .flatMap {
        case None =>
          DBIO.failed(SqlError(s"No channel with node id $nodeId exists"))
        case Some(channel) => DBIO.successful(channel)
      }
  }

}
