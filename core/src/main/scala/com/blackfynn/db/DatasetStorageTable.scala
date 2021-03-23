package com.pennsieve.db

import com.pennsieve.models._
import com.pennsieve.traits.PostgresProfile.api._

import scala.concurrent.ExecutionContext

final class DatasetStorageTable(schema: String, tag: Tag)
    extends Table[DatasetStorage](tag, Some(schema), "dataset_storage") {

  def datasetId = column[Int]("dataset_id", O.PrimaryKey)
  def size = column[Option[Long]]("size")

  def * = (datasetId, size).mapTo[DatasetStorage]
}

class DatasetStorageMapper(val organization: Organization)
    extends TableQuery(new DatasetStorageTable(organization.schemaId, _)) {

  def incrementDataset(
    datasetId: Int,
    size: Long
  )(implicit
    ec: ExecutionContext
  ): DBIO[Int] =
    sql"""
       INSERT INTO "#${organization.schemaId}".dataset_storage
       AS dataset_storage (dataset_id, size)
       VALUES ($datasetId, $size)
       ON CONFLICT (dataset_id)
       DO UPDATE SET size = COALESCE(dataset_storage.size, 0) + EXCLUDED.size
       """
      .as[Int]
      .map(_.headOption.getOrElse(0))

  def setDataset(
    datasetId: Int,
    size: Long
  )(implicit
    ec: ExecutionContext
  ): DBIO[Int] =
    sql"""
       INSERT INTO "#${organization.schemaId}".dataset_storage (dataset_id, size)
       VALUES ($datasetId, $size)
       ON CONFLICT (dataset_id)
       DO UPDATE SET size = EXCLUDED.size
       """
      .as[Int]
      .map(_.headOption.getOrElse(0))
}
