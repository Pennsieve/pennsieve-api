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

package com.pennsieve.etl.`data-cli`

import com.pennsieve.core.utilities.DatabaseContainer
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.db.OrganizationsMapper
import com.pennsieve.etl.`data-cli`.container._
import com.pennsieve.models.{ NodeCodes, Organization }
import com.pennsieve.test._
import com.pennsieve.test.helpers.{ TestDatabase }
import com.typesafe.config.{ Config, ConfigFactory, ConfigValueFactory }
import org.scalatest._

trait DataCLISpecHarness
    extends SuiteMixin
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with TestDatabase
    with PersistantTestContainers
    with PostgresDockerContainer { self: Suite =>

  var dataCLIContainer: Container = _

  val organization: Organization = Organization(
    nodeId = NodeCodes.generateId(NodeCodes.organizationCode),
    name = "ETL Data CLI Test",
    slug = "etl-data-cli-test",
    encryptionKeyId = Some("test-encryption-key"),
    id = 1
  )

  override def beforeEach(): Unit = {
    super.beforeEach()

    if (dataCLIContainer == null) {
      throw new RuntimeException(
        s"dataCLIContainer property of ${this.getClass.getSimpleName} is null. Aborting tests."
      )
    }

    dataCLIContainer.db.run(clearDB).await

    dataCLIContainer.db.run(OrganizationsMapper += organization).await
    dataCLIContainer.db.run(createSchema(organization.schemaId)).await
    migrateOrganizationSchema(
      organization.id,
      dataCLIContainer.postgresDatabase
    )
  }

  override def afterEach: Unit = {
    dataCLIContainer.db.run(clearOrganizationSchema(organization.id)).await
    super.afterEach()
  }

  override def afterStart(): Unit = {
    super.afterStart()

    dataCLIContainer = new DataCLIContainer(config) with DatabaseContainer {
      override val postgresUseSSL: Boolean = false
    }
  }

  override def afterAll(): Unit = {
    dataCLIContainer.db.close()
    super.afterAll()
  }

  def config: Config = {
    ConfigFactory
      .empty()
      .withValue("environment", ConfigValueFactory.fromAnyRef("test"))
      .withFallback(postgresContainer.config)
  }
}
