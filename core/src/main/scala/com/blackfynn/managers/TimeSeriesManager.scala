// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.managers

import cats.data.EitherT
import cats.implicits._
import com.pennsieve.core.utilities.checkOrErrorT
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.db.ChannelsMapper
import com.pennsieve.domain.{ CoreError, NotFound, PredicateError }
import com.pennsieve.models.{
  Channel,
  DBPermission,
  ModelProperty,
  NodeCodes,
  Organization,
  Package
}
import com.pennsieve.traits.PostgresProfile.api._

import scala.collection.SortedSet
import scala.concurrent.{ ExecutionContext, Future }

class TimeSeriesManager(db: Database, organization: Organization) {

  val table = new ChannelsMapper(organization)

  def createChannel(
    `package`: Package,
    name: String,
    start: Long,
    end: Long,
    unit: String,
    rate: Double,
    `type`: String,
    group: Option[String],
    lastAnnotation: Long,
    spikeDuration: Option[Long] = None,
    properties: List[ModelProperty] = Nil
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Channel] = {
    val nodeId = NodeCodes.generateId(NodeCodes.channelCode)
    val query = table returning table += Channel(
      nodeId,
      `package`.id,
      name.trim,
      start,
      end,
      unit,
      rate,
      `type`,
      group,
      lastAnnotation,
      spikeDuration,
      properties
    )
    db.run(query.transactionally).toEitherT
  }

  def updateChannel(
    channel: Channel,
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Channel] =
    db.run(
        table
          .filter(_.id === channel.id)
          .filter(_.packageId === `package`.id)
          .update(channel)
      )
      .map(_ => channel)
      .toEitherT

  def updateChannels(
    channels: List[Channel],
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[Channel]] =
    db.run(
        DBIO
          .sequence(
            channels
              .map(
                channel =>
                  table
                    .filter(_.id === channel.id)
                    .filter(_.packageId === `package`.id)
                    .update(channel)
              )
          )
          .transactionally
      )
      .map(_ => channels)
      .toEitherT

  def deleteChannel(
    channel: Channel,
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Int] =
    db.run(
        table
          .filter(_.id === channel.id)
          .filter(_.packageId === `package`.id)
          .delete
      )
      .toEitherT

  def getChannels(
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[Channel]] =
    db.run(
        table
          .filter(_.packageId === `package`.id)
          .result
      )
      .map(_.toList)
      .toEitherT

  def getChannelsByNodeIds(
    `package`: Package,
    channelIds: SortedSet[String]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[Channel]] =
    for {
      foundChannels <- db
        .run(
          table
            .filter(_.packageId === `package`.id)
            .filter(_.nodeId.inSet(channelIds))
            .result
        )
        .map(_.toList)
        .toEitherT
      missingChannelIds = channelIds diff foundChannels.map(_.nodeId).toSet
      _ <- checkOrErrorT[CoreError](missingChannelIds.size == 0) {
        PredicateError(
          s"Given channels are not part of this TimeSeries: ${missingChannelIds.take(5).mkString("\n", "\n", "\n")}..."
        )
      }
    } yield foundChannels

  def getChannel(
    id: Int,
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Channel] =
    db.run(
        table
          .filter(_.id === id)
          .filter(_.packageId === `package`.id)
          .result
          .headOption
      )
      .whenNone[CoreError](
        NotFound(s"Channel ($id) in Package ${`package`.id}")
      )

  def getChannelByNodeId(
    nodeId: String,
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Channel] =
    db.run(
        table
          .filter(_.nodeId === nodeId)
          .filter(_.packageId === `package`.id)
          .result
          .headOption
      )
      .whenNone[CoreError](NotFound(nodeId))

  /*
   * For pennsieve-streaming server to pull the Channel object from the DB without a Package object.
   */
  def insecure_getChannelByNodeId(
    nodeId: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Channel] = {
    db.run(
        table
          .filter(_.nodeId === nodeId)
          .result
          .headOption
      )
      .whenNone(NotFound(nodeId))
  }

}
