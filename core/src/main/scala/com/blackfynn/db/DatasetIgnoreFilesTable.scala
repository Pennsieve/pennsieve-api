package com.pennsieve.db

import com.pennsieve.models.{ DatasetIgnoreFile, Organization }
import com.pennsieve.traits.PostgresProfile.api._
import scala.concurrent.ExecutionContext

final class DatasetIgnoreFilesTable(schema: String, tag: Tag)
    extends Table[DatasetIgnoreFile](tag, Some(schema), "dataset_ignore_files") {

  def datasetId = column[Int]("dataset_id")
  def fileName = column[String]("file_name")
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)

  def * = (datasetId, fileName, id).mapTo[DatasetIgnoreFile]
}

class DatasetIgnoreFilesMapper(val organization: Organization)
    extends TableQuery(new DatasetIgnoreFilesTable(organization.schemaId, _)) {

  def getIgnoreFilesByDatasetId(
    datasetId: Int
  ): Query[DatasetIgnoreFilesTable, DatasetIgnoreFile, Seq] = {
    this.filter(_.datasetId === datasetId)
  }
}
