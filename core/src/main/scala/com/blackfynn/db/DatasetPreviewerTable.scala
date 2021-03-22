// Copyright (c) 2020 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.db

import com.blackfynn.models._
import com.blackfynn.traits.PostgresProfile.api._
import java.time.ZonedDateTime

import cats.Semigroup
import cats.implicits._
import com.rms.miu.slickcats.DBIOInstances._
import slick.lifted.Case._
import java.util.UUID

import com.blackfynn.domain.SqlError
import com.blackfynn.traits.PostgresProfile

import scala.concurrent.ExecutionContext

final class DatasetPreviewerTable(schema: String, tag: Tag)
    extends Table[DatasetPreviewer](tag, Some(schema), "dataset_previewer") {

  // set by the database
  def datasetId = column[Int]("dataset_id")
  def userId = column[Int]("user_id")
  def embargoAccess = column[EmbargoAccess]("embargo_access")
  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)
  def updatedAt = column[ZonedDateTime]("updated_at", O.AutoInc)
  def dataUseAgreementId = column[Option[Int]]("data_use_agreement_id")

  def pk = primaryKey("combined_pk", (datasetId, userId))

  def * =
    (datasetId, userId, embargoAccess, dataUseAgreementId, createdAt, updatedAt)
      .mapTo[DatasetPreviewer]
}

class DatasetPreviewerMapper(val organization: Organization)
    extends TableQuery(new DatasetPreviewerTable(organization.schemaId, _)) {

  def getPreviewers(
    dataset: Dataset
  ): Query[(DatasetPreviewerTable, UserTable), (DatasetPreviewer, User), Seq] =
    this
      .filter(_.datasetId === dataset.id)
      .join(UserMapper)
      .on(_.userId === _.id)

  def removeAccess(datasetId: Int, userId: Int) =
    this
      .filter(_.userId === userId)
      .filter(_.datasetId === datasetId)
      .delete

  def getByDatasetIdAndUserId(datasetId: Int, userId: Int) =
    this
      .filter(_.userId === userId)
      .filter(_.datasetId === datasetId)
      .result
      .headOption

  def canPreview(
    datasetId: Int,
    userId: Int
  )(implicit
    ec: ExecutionContext
  ) =
    getByDatasetIdAndUserId(datasetId = datasetId, userId = userId).map {
      case Some(preview) if preview.embargoAccess == EmbargoAccess.Granted =>
        true
      case _ => false
    }
}
