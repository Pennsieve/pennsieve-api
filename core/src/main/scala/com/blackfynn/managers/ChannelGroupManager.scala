package com.blackfynn.managers

import com.blackfynn.traits.PostgresProfile.api._
import com.blackfynn.db.ChannelGroup
import com.blackfynn.db.ChannelGroupTable
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
