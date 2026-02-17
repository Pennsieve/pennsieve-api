/*
 * Copyright 2021 University of Pennsylvania
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pennsieve.managers

import com.pennsieve.db._
import com.pennsieve.models.{ Dataset, Organization, User }
import com.pennsieve.traits.PostgresProfile.api._
import org.scalatest.EitherValues._
import io.circe.Json
import io.circe.syntax._

import java.time.ZonedDateTime
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global

class MetadataManagerSpec extends BaseManagerSpec {

  def metadataManager(
    organization: Organization = testOrganization,
    user: User = superAdmin
  ): MetadataManager = {
    new MetadataManager(database, user, organization)
  }

  def createModel(
    dataset: Dataset,
    name: String,
    displayName: String = "Test Model",
    organization: Organization = testOrganization
  ): Model = {
    val modelsMapper = new ModelsMapper(organization)
    val model = Model(
      id = UUID.randomUUID(),
      datasetId = dataset.id,
      name = name,
      displayName = displayName,
      description = "Test model description",
      createdAt = ZonedDateTime.now(),
      updatedAt = ZonedDateTime.now()
    )
    database.run((modelsMapper += model).andThen(DBIO.successful(model))).await
  }

  def createModelVersion(
    model: Model,
    version: Int = 1,
    organization: Organization = testOrganization
  ): ModelVersion = {
    val modelVersionsMapper = new ModelVersionsMapper(organization)
    val modelVersion = ModelVersion(
      modelId = model.id,
      version = version,
      schema = Json.obj("type" -> "object".asJson),
      schemaHash = "test-hash",
      keyProperties = List("id"),
      sensitiveProperties = List(),
      createdAt = ZonedDateTime.now(),
      modelTemplateId = None,
      modelTemplateVersion = None
    )
    database
      .run(
        (modelVersionsMapper += modelVersion)
          .andThen(DBIO.successful(modelVersion))
      )
      .await
  }

  def createRecord(
    dataset: Dataset,
    model: Model,
    modelVersion: Int = 1,
    isCurrent: Boolean = true,
    organization: Organization = testOrganization
  ): Record = {
    val recordsMapper = new RecordsMapper(organization)
    val record = Record(
      sortKey = 0L, // Will be auto-generated
      id = UUID.randomUUID(),
      datasetId = dataset.id,
      modelId = model.id,
      modelVersion = modelVersion,
      value = Json.obj("test" -> "data".asJson),
      valueEncrypted = None,
      validFrom = ZonedDateTime.now(),
      validTo = if (isCurrent) None else Some(ZonedDateTime.now()),
      provenanceId = UUID.randomUUID(),
      createdAt = ZonedDateTime.now(),
      keyHash = None
    )
    database
      .run((recordsMapper += record).andThen(DBIO.successful(record)))
      .await
  }

  "getModelRecordCounts" should "return model names with their current record counts" in {
    val user = createUser()
    val dataset = createDataset(testOrganization, user)

    val patientModel = createModel(dataset, "patient", "Patient")
    val sampleModel = createModel(dataset, "sample", "Sample")

    createModelVersion(patientModel)
    createModelVersion(sampleModel)

    // Create 3 current records for patient
    createRecord(dataset, patientModel, isCurrent = true)
    createRecord(dataset, patientModel, isCurrent = true)
    createRecord(dataset, patientModel, isCurrent = true)

    // Create 2 current records for sample
    createRecord(dataset, sampleModel, isCurrent = true)
    createRecord(dataset, sampleModel, isCurrent = true)

    // Create 1 non-current record for patient (should not be counted)
    createRecord(dataset, patientModel, isCurrent = false)

    val mm = metadataManager(testOrganization, user)
    val counts = mm.getModelRecordCounts(dataset).await.value

    assert(counts.length === 2)

    val patientCount = counts.find(_.modelName === "patient")
    assert(patientCount.isDefined)
    assert(patientCount.get.count === 3)

    val sampleCount = counts.find(_.modelName === "sample")
    assert(sampleCount.isDefined)
    assert(sampleCount.get.count === 2)
  }

  "getModelRecordCounts" should "aggregate counts across multiple model versions" in {
    val user = createUser()
    val dataset = createDataset(testOrganization, user)

    val patientModel = createModel(dataset, "patient", "Patient")

    createModelVersion(patientModel, version = 1)
    createModelVersion(patientModel, version = 2)
    createModelVersion(patientModel, version = 3)

    // Create records across different versions
    createRecord(dataset, patientModel, modelVersion = 1, isCurrent = true)
    createRecord(dataset, patientModel, modelVersion = 1, isCurrent = true)
    createRecord(dataset, patientModel, modelVersion = 2, isCurrent = true)
    createRecord(dataset, patientModel, modelVersion = 3, isCurrent = true)

    val mm = metadataManager(testOrganization, user)
    val counts = mm.getModelRecordCounts(dataset).await.value

    assert(counts.length === 1)
    assert(counts.head.modelName === "patient")
    assert(counts.head.count === 4)
  }

  "getModelRecordCounts" should "return empty list when dataset has no records" in {
    val user = createUser()
    val dataset = createDataset(testOrganization, user)

    val patientModel = createModel(dataset, "patient", "Patient")
    createModelVersion(patientModel)

    val mm = metadataManager(testOrganization, user)
    val counts = mm.getModelRecordCounts(dataset).await.value

    assert(counts.isEmpty)
  }

  "getModelRecordCounts" should "only count current records" in {
    val user = createUser()
    val dataset = createDataset(testOrganization, user)

    val patientModel = createModel(dataset, "patient", "Patient")
    createModelVersion(patientModel)

    // Create 5 non-current records
    createRecord(dataset, patientModel, isCurrent = false)
    createRecord(dataset, patientModel, isCurrent = false)
    createRecord(dataset, patientModel, isCurrent = false)
    createRecord(dataset, patientModel, isCurrent = false)
    createRecord(dataset, patientModel, isCurrent = false)

    val mm = metadataManager(testOrganization, user)
    val counts = mm.getModelRecordCounts(dataset).await.value

    assert(counts.isEmpty)
  }
}
