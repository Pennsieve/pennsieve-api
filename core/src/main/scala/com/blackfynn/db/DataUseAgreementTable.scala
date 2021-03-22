// Copyright (c) 2020 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.db

import java.time.ZonedDateTime

import com.blackfynn.domain._
import com.blackfynn.models._
import com.blackfynn.traits.PostgresProfile.api._

import scala.concurrent.ExecutionContext

/**
  * Data use agreements for embargoed datasets.
  *
  * Must be signed by users before they are granted access to embargoed
  * datasets.
  *
  * Each organization can have a "default" data use agreement. This is used for
  * all newly created datasets.
  */
class DataUseAgreementTable(schema: String, tag: Tag)
    extends Table[DataUseAgreement](tag, Some(schema), "data_use_agreements") {

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def name = column[String]("name")
  def description = column[String]("description")
  def body = column[String]("body")
  def isDefault = column[Boolean]("is_default")
  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)

  def * =
    (name, description, body, isDefault, createdAt, id).mapTo[DataUseAgreement]
}

class DataUseAgreementMapper(organization: Organization)
    extends TableQuery(new DataUseAgreementTable(organization.schemaId, _)) {

  def get(
    agreementId: Int
  )(implicit
    ec: ExecutionContext
  ): DBIO[DataUseAgreement] =
    this
      .filter(_.id === agreementId)
      .result
      .headOption
      .flatMap {
        case Some(agreement) => DBIO.successful(agreement)
        case None => DBIO.failed(NotFound(s"Agreement $agreementId"))
      }
}
