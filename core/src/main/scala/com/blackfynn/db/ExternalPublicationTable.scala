// Copyright (c) 2020 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.db

import java.time.ZonedDateTime

import com.pennsieve.domain.Error
import com.pennsieve.models._
import com.pennsieve.traits.PostgresProfile.api._
import scala.concurrent.ExecutionContext

/**
  * External publications are DOIs published outside of Discover.  They are used
  * to reference related publications, for example: citations or papers which
  * use the data published in the dataset.
  */
class ExternalPublicationTable(schema: String, tag: Tag)
    extends Table[ExternalPublication](
      tag,
      Some(schema),
      "external_publications"
    ) {

  def datasetId = column[Int]("dataset_id")
  def doi = column[Doi]("doi")
  def relationshipType = column[RelationshipType]("relationship_type")

  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)
  def updatedAt = column[ZonedDateTime]("updated_at", O.AutoInc)

  def pk = primaryKey("combined_pk", (datasetId, doi, relationshipType))

  def * =
    (datasetId, doi, relationshipType, createdAt, updatedAt)
      .mapTo[ExternalPublication]
}

class ExternalPublicationMapper(organization: Organization)
    extends TableQuery(new ExternalPublicationTable(organization.schemaId, _)) {

  def createOrUpdate(
    dataset: Dataset,
    doi: Doi,
    relationshipType: RelationshipType
  )(implicit
    ec: ExecutionContext
  ): DBIO[ExternalPublication] =
    for {
      _ <- this.insertOrUpdate(
        ExternalPublication(dataset.id, doi, relationshipType)
      )

      externalPublication <- getByDoiAndRelationshipType(
        dataset,
        doi,
        relationshipType
      ).result.headOption.flatMap {
        case Some(p) => DBIO.successful(p)
        case None => DBIO.failed(Error("Could not get publication"))
      }
    } yield externalPublication

  def get(
    dataset: Dataset
  ): Query[ExternalPublicationTable, ExternalPublication, Seq] =
    this
      .filter(_.datasetId === dataset.id)

  def getByDoiAndRelationshipType(
    dataset: Dataset,
    doi: Doi,
    relationshipType: RelationshipType
  ): Query[ExternalPublicationTable, ExternalPublication, Seq] =
    get(dataset)
      .filter(_.doi === doi)
      .filter(_.relationshipType === relationshipType)

  def delete(
    dataset: Dataset,
    doi: Doi,
    relationshipType: RelationshipType
  ): DBIO[Int] =
    getByDoiAndRelationshipType(dataset, doi, relationshipType).delete

}
