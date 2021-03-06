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

package com.pennsieve.clients

import java.time.OffsetDateTime
import java.util.UUID

import com.pennsieve.concepts.types.CreateProxyInstancePayload
import com.pennsieve.domain.CoreError
import com.pennsieve.dtos.{ ConceptDTO, ConceptInstanceDTO }
import com.pennsieve.models.NodeId
import io.circe.syntax._
import io.circe.Json
import org.apache.http.impl.client.HttpClients

class MockModelServiceClient
    extends ModelServiceClient(HttpClients.createMinimal(), "mock-host", 0) {

  var conceptCalledCount = 0

  var instanceCalledCount = 0

  var linkCalledCount = 0

  var getModelStatsCalledCount = 0

  val mockConceptDTO = ConceptDTO(
    name = "mock-concept",
    displayName = "Mock Concept",
    description = "Just a fake concept",
    createdBy = new NodeId("N", "mock", UUID.randomUUID()),
    updatedBy = new NodeId("N", "mock", UUID.randomUUID()),
    locked = false,
    id = UUID.randomUUID(),
    count = 1,
    createdAt = OffsetDateTime.now(),
    updatedAt = OffsetDateTime.now()
  )

  val mockConceptInstanceDTO = ConceptInstanceDTO(
    id = UUID.randomUUID(),
    `type` = "package",
    values = List(),
    createdAt = OffsetDateTime.now(),
    updatedAt = OffsetDateTime.now(),
    createdBy = new NodeId("N", "mock", UUID.randomUUID()),
    updatedBy = new NodeId("N", "mock", UUID.randomUUID())
  )

  def clearCounts(): Unit = {
    conceptCalledCount = 0
    instanceCalledCount = 0
    linkCalledCount = 0
    getModelStatsCalledCount = 0
  }

  override def concept[B: ToBearer](
    token: B,
    datasetId: String,
    conceptId: String
  ): Either[CoreError, ConceptDTO] = {
    conceptCalledCount += 1
    Right(mockConceptDTO)
  }

  override def instance[B: ToBearer](
    token: B,
    datasetId: String,
    conceptId: String,
    instanceId: String
  ): Either[CoreError, ConceptInstanceDTO] = {
    instanceCalledCount += 1
    Right(mockConceptInstanceDTO)
  }

  override def getModelStats[B: ToBearer](
    token: B,
    datasetId: String
  ): Either[CoreError, Map[String, Int]] = {
    getModelStatsCalledCount += 1
    Right(Map("conceptName" -> 1))
  }

  override def link[B: ToBearer](
    token: B,
    datasetId: String,
    proxyType: String,
    payload: CreateProxyInstancePayload
  ): Either[CoreError, Boolean] = {
    linkCalledCount += 1
    Right(true)
  }
}
