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

package com.pennsieve.test.helpers

import com.pennsieve.core.utilities._
import com.pennsieve.test._
import org.scalatest.{
  BeforeAndAfterAll,
  BeforeAndAfterEach,
  Suite,
  SuiteMixin
}
import com.typesafe.config.{ Config, ConfigFactory, ConfigValueFactory }

trait CoreSpecHarness[
  Container <: OrganizationManagerContainer with UserManagerContainer
] extends SuiteMixin
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with CoreSeed[Container]
    with TestDatabase
    with PersistantTestContainers
    with PostgresDockerContainer { self: Suite =>

  var config: Config = _
  var testDIContainer: Container = _

  def createTestDIContainer: Container

  override def afterStart(): Unit = {
    super.afterStart()
    config = ConfigFactory
      .empty()
      .withFallback(postgresContainer.config)
      .withValue("email.host", ConfigValueFactory.fromAnyRef("test"))
      .withValue(
        "email.trials_host",
        ConfigValueFactory.fromAnyRef("trials_test")
      )
      .withValue(
        "email.support_email",
        ConfigValueFactory.fromAnyRef("support@pennsieve.org")
      )
      .withValue("environment", ConfigValueFactory.fromAnyRef("test"))
    testDIContainer = createTestDIContainer
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    if (testDIContainer == null) {
      throw new RuntimeException(
        s"testDIContainer property of ${this.getClass.getName} is null. Aborting tests."
      )
    }

    testDIContainer.db.run(clearDB).await

    seed(testDIContainer)
  }

  override def afterEach(): Unit = {
    testDIContainer.db.run(clearSchemas).await
    super.afterEach()
  }

  override def afterAll(): Unit = {
    if (testDIContainer != null) {
      testDIContainer.db.close()
    }
    super.afterAll()
  }
}
