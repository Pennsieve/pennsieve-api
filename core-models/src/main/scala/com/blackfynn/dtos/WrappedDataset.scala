// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.dtos

import java.time.ZonedDateTime

import com.blackfynn.models.{
  Dataset,
  DatasetState,
  DatasetStatus,
  DatasetType,
  DefaultDatasetStatus,
  License
}
import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.java8.time._

case class WrappedDataset(
  id: String,
  name: String,
  description: Option[String],
  state: DatasetState = DatasetState.READY,
  createdAt: ZonedDateTime,
  updatedAt: ZonedDateTime,
  packageType: String = "DataSet", // DO NOT SET: For backwards compatibility with the frontend (Polymer v.1) application
  datasetType: DatasetType, // Do not change the name of this property. If you change it to something like `type` it'll break the python client
  status: String,
  automaticallyProcessPackages: Boolean = false,
  license: Option[License],
  tags: List[String] = List.empty,
  dataUseAgreementId: Option[Int] = None,
  intId: Int
)

object WrappedDataset {
  def apply(dataset: Dataset, status: DatasetStatus): WrappedDataset = {
    WrappedDataset(
      id = dataset.nodeId,
      name = dataset.name,
      description = dataset.description,
      state = dataset.state,
      createdAt = dataset.createdAt,
      updatedAt = dataset.updatedAt,
      datasetType = dataset.`type`,
      status = status.name,
      automaticallyProcessPackages = dataset.automaticallyProcessPackages,
      license = dataset.license,
      tags = dataset.tags,
      dataUseAgreementId = dataset.dataUseAgreementId,
      intId = dataset.id
    )
  }

  implicit val encoder: Encoder[WrappedDataset] = deriveEncoder[WrappedDataset]
  implicit val decoder: Decoder[WrappedDataset] = deriveDecoder[WrappedDataset]

}
