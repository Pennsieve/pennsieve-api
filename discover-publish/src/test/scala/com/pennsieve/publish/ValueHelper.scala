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

package com.pennsieve.publish
import com.pennsieve.models.{
  Dataset,
  DatasetState,
  Doi,
  NodeCodes,
  Organization,
  PublishedCollection,
  PublishedContributor,
  PublishedExternalPublication,
  RelationshipType,
  User
}

import scala.util.Random

trait ValueHelper {

  val sampleOrganization: Organization =
    Organization("N:organization:32352", "Test org", "test-org", id = 5)
  val contributor: PublishedContributor =
    PublishedContributor(
      first_name = "John",
      middle_initial = Some("G"),
      last_name = "Malkovich",
      degree = None,
      orcid = Some("0000-0003-8769-1234")
    )
  val collection: PublishedCollection = PublishedCollection(
    "My awesome collection"
  )
  val externalPublication: PublishedExternalPublication =
    PublishedExternalPublication(
      Doi("10.26275/t6j6-77pu"),
      Some(RelationshipType.References)
    )
  def generateRandomString(size: Int = 10): String =
    Random.alphanumeric.filter(_.isLetter).take(size).mkString

  def newUser(
    email: String = s"test+${generateRandomString()}@pennsieve.org",
    isSuperAdmin: Boolean = false
  ): User =
    User(
      nodeId = NodeCodes.generateId(NodeCodes.userCode),
      email = email,
      firstName = "Test",
      middleInitial = None,
      lastName = "User",
      degree = None,
      isSuperAdmin = isSuperAdmin
    )

  def newDataset(
    name: String = generateRandomString(),
    statusId: Int = 1,
    description: Option[String] = Some("description")
  ): Dataset = {

    Dataset(
      NodeCodes.generateId(NodeCodes.dataSetCode),
      name,
      DatasetState.READY,
      description = description,
      statusId = statusId
    )
  }

}
