// Copyright (c) 2020 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.db

import java.time.ZonedDateTime

import com.blackfynn.models._
import com.blackfynn.traits.PostgresProfile.api._

import scala.concurrent.ExecutionContext

class DatasetCollectionTable(schema: String, tag: Tag)
    extends Table[DatasetCollection](tag, Some(schema), "dataset_collection") {

  def datasetId = column[Int]("dataset_id")
  def collectionId = column[Int]("collection_id")

  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)
  def updatedAt = column[ZonedDateTime]("updated_at", O.AutoInc)

  def pk = primaryKey("combined_pk", (datasetId, collectionId))

  def * =
    (datasetId, collectionId, createdAt, updatedAt)
      .mapTo[DatasetCollection]
}

class DatasetCollectionMapper(organization: Organization)
    extends TableQuery(new DatasetCollectionTable(organization.schemaId, _)) {

  implicit val collectionMapper: CollectionMapper = new CollectionMapper(
    organization
  )

  def getBy(
    dataset: Dataset,
    collectionId: Int
  ): Query[DatasetCollectionTable, DatasetCollection, Seq] =
    this
      .filter(_.collectionId === collectionId)
      .filter(_.datasetId === dataset.id)

  def getCollections(
    dataset: Dataset
  ): Query[CollectionTable, Collection, Seq] =
    this
      .filter(_.datasetId === dataset.id)
      .join(collectionMapper)
      .on(_.collectionId === _.id)
      .map {
        case (d, c) => c
      }
}
