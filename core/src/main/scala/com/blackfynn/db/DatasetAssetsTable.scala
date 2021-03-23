package com.pennsieve.db

import java.time.ZonedDateTime
import java.util.UUID

import com.pennsieve.domain.SqlError
import com.pennsieve.models.{ DatasetAsset, Organization }
import com.pennsieve.traits.PostgresProfile.api._

import scala.concurrent.ExecutionContext

final class DatasetAssetsTable(schema: String, tag: Tag)
    extends Table[DatasetAsset](tag, Some(schema), "dataset_assets") {

  def name = column[String]("name")
  def s3Bucket = column[String]("s3_bucket")
  def s3Key = column[String]("s3_key")
  def datasetId = column[Int]("dataset_id")
  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)
  def updatedAt = column[ZonedDateTime]("updated_at", O.AutoInc)
  def id = column[UUID]("id", O.PrimaryKey)

  def * =
    (name, s3Bucket, s3Key, datasetId, createdAt, updatedAt, id)
      .mapTo[DatasetAsset]
}

class DatasetAssetsMapper(val organization: Organization)
    extends TableQuery(new DatasetAssetsTable(organization.schemaId, _)) {

  def get(id: UUID): Query[DatasetAssetsTable, DatasetAsset, Seq] =
    this.filter(_.id === id)

  def getDatasetAsset(
    id: UUID
  )(implicit
    executionContext: ExecutionContext
  ): DBIO[DatasetAsset] = {
    this
      .get(id)
      .result
      .headOption
      .flatMap {
        case None =>
          DBIO.failed(SqlError(s"No dataset asset with id $id exists"))
        case Some(asset) => DBIO.successful(asset)
      }
  }

  def getDatasetAssets(ids: Set[UUID]): DBIO[Seq[DatasetAsset]] = {
    this.filter(_.id.inSet(ids)).result
  }

  def getByDatasetId(
    datasetId: Int
  ): Query[DatasetAssetsTable, DatasetAsset, Seq] =
    this.filter(_.datasetId === datasetId)

}
