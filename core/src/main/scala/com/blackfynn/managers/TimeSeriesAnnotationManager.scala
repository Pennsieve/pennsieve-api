package com.pennsieve.managers

import akka.NotUsed
import akka.stream.scaladsl.Source
import cats.data._
import cats.implicits._
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.db.{
  ChannelGroup,
  ChannelGroupTable,
  DBTimeSeriesAnnotation,
  TimeSeriesAnnotation,
  TimeSeriesAnnotationTable,
  TimeSeriesLayer,
  TimeSeriesLayerTable
}
import com.pennsieve.domain.CoreError
import com.pennsieve.timeseries.{
  AnnotationAggregateWindowResult,
  AnnotationChunker,
  AnnotationData,
  AnnotationEvent,
  AnnotationWindow,
  WindowAggregator
}
import com.github.tminglei.slickpg.Range
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.models.Package
import slick.dbio.Effect
import slick.sql.FixedSqlAction
import slick.jdbc.{ ResultSetConcurrency, ResultSetType }

import scala.collection.SortedSet
import scala.concurrent.{ ExecutionContext, Future }

class TimeSeriesAnnotationManager(val db: Database) {

  lazy val channelGroups: TableQuery[ChannelGroupTable] = new TableQuery(
    new ChannelGroupTable(_)
  )
  lazy val channelGroupTableQuery: TableQuery[ChannelGroupTable] =
    new TableQuery(new ChannelGroupTable(_))
  lazy val channelGroupMapper: ChannelGroupManager =
    new ChannelGroupManager(db, channelGroupTableQuery)
  lazy val timeSeriesAnnotationTableQuery = new TableQuery(
    new TimeSeriesAnnotationTable(_)
  )
  lazy val layerTableQuery = new TableQuery(new TimeSeriesLayerTable(_))
  lazy val layerManager: TimeSeriesLayerManager = new TimeSeriesLayerManager(
    db,
    layerTableQuery,
    timeSeriesAnnotationTableQuery
  )

  /*
   * Because channels are stored in the main Postgres instance, and annotations
   * in the data instance, there is no way to check whether a set of channel
   * ids belong to a given timeseries just in the data DB. Adding a
   * TimeSeriesManager as a property on TimeSeriesAnotationManager requires
   * making TimeSeriesDBContainer a SecureContainer, and would cause sweeping
   * changes across the codebase. It is simpler to pass a TimeSeriesManager
   * to `create` and `update` to perform this check.
   */

  def create(
    `package`: Package,
    layerId: Int,
    name: String,
    label: String,
    description: Option[String],
    userNodeId: String,
    range: Range[Long],
    channelIds: SortedSet[String],
    data: Option[AnnotationData],
    linkedPackage: Option[String] = None
  )(
    timeSeriesManager: TimeSeriesManager
  )(implicit
    executionContext: ExecutionContext
  ): EitherT[Future, CoreError, TimeSeriesAnnotation] = {
    val createAction: DBIO[TimeSeriesAnnotation] = for {
      channelGroup <- DBIO.from(channelGroupMapper.getOrCreate(channelIds))
      newAnnotation = DBTimeSeriesAnnotation(
        0,
        `package`.nodeId,
        channelGroup.id,
        layerId,
        name,
        label,
        description,
        Some(userNodeId),
        range,
        data,
        linkedPackage
      )
      result: DBTimeSeriesAnnotation <- timeSeriesAnnotationTableQuery
        .returning(timeSeriesAnnotationTableQuery) += newAnnotation
    } yield result.toTimeSeriesAnnotation(channelGroup)

    for {
      // check that these channels belong to this timeseries
      _ <- timeSeriesManager.getChannelsByNodeIds(`package`, channelIds)
      result <- db.run(createAction.transactionally).toEitherT
    } yield result

  }

  def update(
    `package`: Package,
    annotation: TimeSeriesAnnotation
  )(
    timeSeriesManager: TimeSeriesManager
  )(implicit
    executionContext: ExecutionContext
  ): EitherT[Future, CoreError, TimeSeriesAnnotation] = {
    val updateAction: DBIO[TimeSeriesAnnotation] = for {
      channelGroup <- DBIO.from(
        channelGroupMapper.getOrCreate(annotation.channelIds)
      )
      _ <- timeSeriesAnnotationTableQuery
        .filter(_.id === annotation.id)
        .update(annotation.toDBTimeSeriesAnnotation(channelGroup))
    } yield annotation

    for {
      // check that these channels belong to this timeseries
      _ <- timeSeriesManager.getChannelsByNodeIds(
        `package`,
        annotation.channelIds
      )
      result <- db.run(updateAction.transactionally).toEitherT
    } yield result
  }

  def delete(
    id: Int
  )(implicit
    executionContext: ExecutionContext
  ): Future[Unit] = {
    val query = timeSeriesAnnotationTableQuery
      .filter(_.id === id)
      .delete
      .map(_ => ())
    db.run(query)
  }

  def delete(timeSeriesId: String): Future[Int] = {
    val query = timeSeriesAnnotationTableQuery
      .filter(_.timeSeriesId === timeSeriesId)
      .delete
    db.run(query)
  }

  def delete(timeSeriesId: String, layerId: Int, ids: Set[Int]): Future[Int] = {
    val query = timeSeriesAnnotationTableQuery
      .filter(_.timeSeriesId === timeSeriesId)
      .filter(_.layerId === layerId)
      .filter(_.id inSet ids)
      .delete
    db.run(query)
  }

  val findBaseQuery: Query[
    (TimeSeriesAnnotationTable, ChannelGroupTable),
    (DBTimeSeriesAnnotation, ChannelGroup),
    Seq
  ] =
    timeSeriesAnnotationTableQuery
      .join(channelGroupMapper.channelGroupTableQuery)
      .on(_.channelGroupId === _.id)
      .sortBy {
        case (timeSeriesAnnotationTable, _) =>
          timeSeriesAnnotationTable.range
      }

  def getBy(
    id: Int
  )(implicit
    executionContext: ExecutionContext
  ): Future[Option[TimeSeriesAnnotation]] = {
    val query = findBaseQuery
      .filter {
        case (timeSeriesAnnotationTable, _) =>
          timeSeriesAnnotationTable.id === id
      }
      .result
      .headOption
      .map(_.map {
        case (timeSeriesDBAnnotation, channelGroup) =>
          timeSeriesDBAnnotation.toTimeSeriesAnnotation(channelGroup)
      })
    db.run(query)
  }

  def findBy(
    channelIds: SortedSet[String]
  )(implicit
    executionContext: ExecutionContext
  ): Future[Seq[TimeSeriesAnnotation]] = {
    val query = findBaseQuery
      .filter {
        case (_, channelGroupTable) =>
          channelGroupTable.channelIds @& channelIds.toList
      }
      .result
      .map(_.map {
        case (dbTimeSeriesAnnotation, channelGroup) =>
          dbTimeSeriesAnnotation.toTimeSeriesAnnotation(channelGroup)
      })
    db.run(query)
  }

  def findBy(
    channelIds: SortedSet[String],
    qStart: Long,
    qEnd: Long,
    layerId: Int,
    limit: Long,
    offset: Long
  )(implicit
    executionContext: ExecutionContext
  ): Future[Seq[TimeSeriesAnnotation]] = {
    val query = findBaseQuery
      .filter {
        case (timeSeriesAnnotationTable, channelGroupTable) =>
          timeSeriesAnnotationTable.range @& Range[Long](qStart, qEnd) &&
            channelGroupTable.channelIds @& channelIds.toList &&
            timeSeriesAnnotationTable.layerId === layerId
      }
      .drop(offset)
      .take(limit)
      .result
      .map(_.map {
        case (dbTimeSeriesAnnotation, channelGroup) =>
          dbTimeSeriesAnnotation.toTimeSeriesAnnotation(channelGroup)
      })
    db.run(query)
  }

  def findBy(
    channelIds: SortedSet[String],
    qStart: Long,
    qEnd: Long,
    layerName: String,
    limit: Long,
    offset: Long
  )(implicit
    executionContext: ExecutionContext
  ): Future[Seq[TimeSeriesAnnotation]] = {
    val query = timeSeriesAnnotationTableQuery
      .join(channelGroupMapper.channelGroupTableQuery)
      .on(_.channelGroupId === _.id)
      .join(layerTableQuery)
      .on {
        case ((timeSeriesAnnotationTable, _), layerTable) =>
          timeSeriesAnnotationTable.layerId === layerTable.id
      }
      .filter {
        case ((timeSeriesAnnotationTable, channelGroupTable), layerTable) =>
          timeSeriesAnnotationTable.range @& Range[Long](qStart, qEnd) &&
            channelGroupTable.channelIds @& channelIds.toList &&
            layerTable.name === layerName
      }
      .map(_._1)
      .sortBy {
        case (timeSeriesAnnotationTable, _) =>
          timeSeriesAnnotationTable.range
      }
      .drop(offset)
      .take(limit)
      .result
      .map(_.map {
        case (dbTimeSeriesAnnotation, channelGroup) =>
          dbTimeSeriesAnnotation.toTimeSeriesAnnotation(channelGroup)
      })
    db.run(query)
  }

  def findBy(
    timeSeriesId: String
  )(implicit
    executionContext: ExecutionContext
  ): Future[Seq[TimeSeriesAnnotation]] = {
    val query = findBaseQuery
      .filter(_._1.timeSeriesId === timeSeriesId)
      .result
      .map(_.map {
        case (dbTimeSeriesAnnotation, channelGroup) =>
          dbTimeSeriesAnnotation.toTimeSeriesAnnotation(channelGroup)
      })
    db.run(query)
  }

  def hasAnnotations(tsPackageId: String): Future[Boolean] = {
    val query = timeSeriesAnnotationTableQuery
      .filter(_.timeSeriesId === tsPackageId)
      .exists
      .result
    db.run(query)
  }

  def windowAggregateQuery(
    range: Range[Long],
    layerId: Int,
    channelIds: SortedSet[String]
  ): Query[
    (Rep[Long], Rep[Long], Rep[Option[AnnotationData]]),
    (Long, Long, Option[AnnotationData]),
    Seq
  ] =
    findBaseQuery
      .filter {
        case (timeSeriesAnnotationTable, channelGroupTable) =>
          timeSeriesAnnotationTable.range @& range &&
            timeSeriesAnnotationTable.layerId === layerId &&
            channelGroupTable.channelIds @& channelIds.toList
      }
      .map {
        case (timeSeriesAnnotationTable, _) =>
          (
            timeSeriesAnnotationTable.range.lower,
            timeSeriesAnnotationTable.range.upper,
            timeSeriesAnnotationTable.data
          )
      }

  def chunkWindowAggregate[A, F](
    frameStart: Long,
    frameEnd: Long,
    periodLength: Long,
    channelIds: SortedSet[String],
    layerId: Int,
    windowAggregator: WindowAggregator[A, F]
  ): Source[AnnotationAggregateWindowResult[F], NotUsed] = {
    val publisher = db.stream(
      windowAggregateQuery(Range(frameStart, frameEnd), layerId, channelIds).result
      // These extra statement parameters are needed to properly stream from Postgres
      // See https://scala-slick.org/doc/3.2.3/dbio.html#streaming
        .withStatementParameters(
          rsType = ResultSetType.ForwardOnly,
          rsConcurrency = ResultSetConcurrency.ReadOnly,
          fetchSize = 1000
        )
        .transactionally
    )

    Source
      .fromPublisher(publisher)
      .map(AnnotationEvent.tupled)
      .via(
        new AnnotationChunker(
          frameStart,
          frameEnd,
          periodLength,
          windowAggregator
        )
      )
      .map { aggregation =>
        val finalValue = windowAggregator.postAggregator(aggregation.value)
        aggregation.copy(value = finalValue)
      }
      .filter(aggregation => windowAggregator.filter(aggregation.value))
  }

  def windowAggregate[A, F](
    frameStart: Long,
    frameEnd: Long,
    periodLength: Long,
    channelIds: SortedSet[String],
    layerId: Int,
    windowAggregator: WindowAggregator[A, F]
  ): Source[AnnotationAggregateWindowResult[F], NotUsed] = {
    val publisher = db.stream(
      windowAggregateQuery(Range(frameStart, frameEnd), layerId, channelIds).result
      // These extra statement parameters are needed to properly stream from Postgres
      // See https://scala-slick.org/doc/3.2.3/dbio.html#streaming
        .withStatementParameters(
          rsType = ResultSetType.ForwardOnly,
          rsConcurrency = ResultSetConcurrency.ReadOnly,
          fetchSize = 1000
        )
        .transactionally
    )

    Source
      .fromPublisher(publisher)
      .map(AnnotationEvent.tupled)
      .via(
        AnnotationWindow.windowFlow(frameEnd, periodLength, windowAggregator)
      )
  }

  def copyLayers(
    source: Package,
    destination: Package
  )(implicit
    ec: ExecutionContext
  ): DBIOAction[Map[Int, Int], NoStream, Effect.Read with Effect.Write] = {
    for {
      oldLayers <- layerTableQuery
        .filter(_.timeSeriesId === source.nodeId)
        .result
      _newLayers = oldLayers.map(_.copy(timeSeriesId = destination.nodeId))
      newLayers <- DBIO.sequence(
        _newLayers.map(
          newLayer => layerTableQuery returning layerTableQuery += newLayer
        )
      )
      oldIds = oldLayers.map(_.id)
      newIds = newLayers.map(_.id)
    } yield oldIds.zip(newIds).toMap
  }

  def copyAnnotation(
    annotation: DBTimeSeriesAnnotation,
    destination: Package,
    layerMap: Map[Int, Int],
    channelGroupMap: Map[Int, Int]
  ): Option[
    DBIOAction[DBTimeSeriesAnnotation, NoStream, Effect.Read with Effect.Write]
  ] = {
    for {
      newLayerId <- layerMap.get(annotation.layerId)
      newChannelGroupId <- channelGroupMap.get(annotation.channelGroupId)
      newAnnotation = annotation.copy(
        layerId = newLayerId,
        channelGroupId = newChannelGroupId,
        timeSeriesId = destination.nodeId
      )
    } yield {
      timeSeriesAnnotationTableQuery returning timeSeriesAnnotationTableQuery += newAnnotation
    }
  }

  def copyChannelGroup(
    channelMap: Map[String, String],
    oldChannelGroup: ChannelGroup
  ): FixedSqlAction[ChannelGroup, NoStream, Effect.Write] = {
    val oldChannels = oldChannelGroup.channels
    val newChannels = SortedSet(
      oldChannels.flatMap(c => channelMap.get(c)).toList: _*
    )
    channelGroups returning channelGroups += ChannelGroup(0, newChannels)
  }

  def copyChannelGroups(
    channelMap: Map[String, String],
    oldChannelGroupIds: Set[Int]
  )(implicit
    ec: ExecutionContext
  ): DBIOAction[Map[Int, Int], NoStream, Effect.Read with Effect.Write] =
    for {
      oldChannelGroups <- channelGroups
        .filter(_.id.inSet(oldChannelGroupIds))
        .result
      newChannelGroups <- DBIO.sequence(
        oldChannelGroups.map(g => copyChannelGroup(channelMap, g))
      )
      oldIds = oldChannelGroups.map(_.id)
      newIds = newChannelGroups.map(_.id)
    } yield oldIds.zip(newIds).toMap

  def copyPackageAnnotations(
    source: Package,
    destination: Package,
    channelMap: Map[String, String]
  )(implicit
    ec: ExecutionContext
  )
    : DBIOAction[
      Int,
      NoStream,
      Effect.Read with Effect.Read with Effect.Write
    ] = {
    for {
      layerMap <- copyLayers(source, destination)
      oldChannelGroups <- timeSeriesAnnotationTableQuery
        .filter(_.timeSeriesId === source.nodeId)
        .map(_.channelGroupId)
        .result
      channelGroupMap <- copyChannelGroups(channelMap, oldChannelGroups.toSet)
      annotations <- timeSeriesAnnotationTableQuery
        .filter(_.timeSeriesId === source.nodeId)
        .result
      newAnnotations <- DBIO.sequence(
        annotations.flatMap(
          a => copyAnnotation(a, destination, layerMap, channelGroupMap)
        )
      )
    } yield newAnnotations.length
  }
}
