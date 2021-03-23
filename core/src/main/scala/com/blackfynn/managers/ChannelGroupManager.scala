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

package com.pennsieve.managers

import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.db.ChannelGroup
import com.pennsieve.db.ChannelGroupTable
import slick.dbio.Effect
import slick.sql.SqlAction

import scala.collection.SortedSet
import scala.concurrent.{ ExecutionContext, Future }

class ChannelGroupManager(
  val db: Database,
  val channelGroupTableQuery: TableQuery[ChannelGroupTable]
) {

  def create(channelIds: SortedSet[String]): Future[ChannelGroup] = {
    val query = channelGroupTableQuery.returning(channelGroupTableQuery) += ChannelGroup(
      0,
      channelIds
    )
    db.run(query)
  }

  def getByQuery(
    channelIds: SortedSet[String]
  ): Query[ChannelGroupTable, ChannelGroup, Seq] =
    channelGroupTableQuery.filter(_.channelIds === channelIds.toList)

  def getBy(channelIds: SortedSet[String]): Future[Option[ChannelGroup]] = {
    val query = this.getByQuery(channelIds).result.headOption
    db.run(query)
  }

  def getById(
    id: Int
  ): SqlAction[Option[ChannelGroup], NoStream, Effect.Read] = {
    channelGroupTableQuery.filter(_.id === id).result.headOption
  }

  def getOrCreate(
    channelIds: SortedSet[String]
  )(implicit
    executionContext: ExecutionContext
  ): Future[ChannelGroup] = {
    val query = this
      .getByQuery(channelIds)
      .result
      .headOption
      .flatMap {
        case Some(channelGroup) => DBIO.successful(channelGroup)
        case None => DBIO.from(create(channelIds))
      }
    db.run(query)
  }

  def deleteBy(channelIds: SortedSet[String]): Future[Int] = {
    val query = channelGroupTableQuery
      .filter(_.channelIds === channelIds.toList)
      .delete
    db.run(query)
  }
}
