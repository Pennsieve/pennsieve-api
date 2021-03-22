package com.blackfynn.managers

import com.blackfynn.traits.PostgresProfile.api._
import com.blackfynn.db.{
  TimeSeriesAnnotationTable,
  TimeSeriesLayer,
  TimeSeriesLayerTable
}
import com.blackfynn.utilities.AbstractError

import scala.concurrent.{ ExecutionContext, Future }
import scala.reflect.ClassTag

case class OperationFailed[T](
  item: T,
  operation: String
)(implicit
  tag: ClassTag[T]
) extends AbstractError {
  override def getMessage =
    s"Failed to $operation ${tag.runtimeClass.getName}: $item"
}

class TimeSeriesLayerManager(
  val db: Database,
  val layerTableQuery: TableQuery[TimeSeriesLayerTable],
  val timeSeriesAnnotationTableQuery: TableQuery[TimeSeriesAnnotationTable]
) {

  def create(
    timeSeriesId: String,
    name: String,
    description: Option[String] = None,
    color: Option[String] = None
  ): Future[TimeSeriesLayer] = {
    val query = layerTableQuery.returning(layerTableQuery) += TimeSeriesLayer(
      0,
      timeSeriesId,
      name,
      description,
      color
    )
    db.run(query)
  }

  def update(
    layer: TimeSeriesLayer
  )(implicit
    executionContext: ExecutionContext
  ): Future[TimeSeriesLayer] = {
    val query = for {
      updateCount <- layerTableQuery.filter(_.id === layer.id).update(layer)
      _ <- assert(updateCount == 1)(OperationFailed(layer, "update"))
    } yield layer

    db.run(query.transactionally)
  }

  def delete(
    layerId: Int
  )(implicit
    executionContext: ExecutionContext
  ): Future[Unit] = {
    val deleteActions = for {
      _ <- timeSeriesAnnotationTableQuery
        .filter(_.layerId === layerId)
        .delete
      _ <- layerTableQuery.filter(_.id === layerId).delete
    } yield ()

    db.run(deleteActions.transactionally)
  }

  def delete(timeSeriesId: String): Future[Int] = {
    val query = layerTableQuery
      .filter(_.timeSeriesId === timeSeriesId)
      .delete
    db.run(query)
  }

  def getBy(id: Int): Future[Option[TimeSeriesLayer]] = {
    val query = layerTableQuery.filter(_.id === id).result.headOption
    db.run(query)
  }

  def getExistingColors(timeSeriesId: String): Future[Set[String]] = {
    val query = layerTableQuery
      .filter(_.timeSeriesId === timeSeriesId)
      .filter(_.color.isDefined)
      .map(_.color.getOrElse("")) // should never be an empty string cause of isDefined filter check
      .to[Set]
      .result
    db.run(query)
  }

  def findBy(
    timeSeriesId: String,
    limit: Long,
    offset: Long
  ): Future[Seq[TimeSeriesLayer]] = {
    val query = layerTableQuery
      .filter(_.timeSeriesId === timeSeriesId)
      .take(limit)
      .drop(offset)
    db.run(query.result)
  }

  def getLayerIds(
    timeSeriesId: String
  )(implicit
    executionContext: ExecutionContext
  ): Future[Set[Int]] = {
    val query =
      layerTableQuery.filter(_.timeSeriesId === timeSeriesId).map(_.id)
    db.run(query.result).map(_.toSet)
  }
}
