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

package com.pennsieve.migrations.organizations

import com.pennsieve.core.utilities.DatabaseContainer
import com.pennsieve.db.UserMapper
import com.pennsieve.managers.BaseManagerSpec
import com.pennsieve.models.{ Organization, User }
import com.pennsieve.test.S3DockerContainer
import com.pennsieve.utilities.`unused-organization-migration`.UnusedOrganizationMigrationContainer
import com.typesafe.config.{ Config, ConfigFactory, ConfigValueFactory }
import com.pennsieve.traits.PostgresProfile.api._
import org.scalatest.matchers.should.Matchers
import org.scalatest.EitherValues._

import scala.concurrent.ExecutionContext.Implicits.global

class TestUnusedOrganizationMigration
    extends BaseManagerSpec
    with S3DockerContainer
    with Matchers {

  implicit def config: Config =
    ConfigFactory
      .empty()
      .withFallback(postgresContainer.config)
      .withValue("environment", ConfigValueFactory.fromAnyRef("local"))

  def createMigrationContainer(
    implicit
    config: Config
  ): UnusedOrganizationMigrationContainer =
    new UnusedOrganizationMigrationContainer(config, dryRun = false)

  def setPreferredOrg(
    db: Database,
    user: User,
    organization: Organization
  ): Unit = {
    val updateUserPreferredOrg = (for {
      u <- UserMapper if u.id === user.id
    } yield u.preferredOrganizationId).update(Some(organization.id))
    db.run(updateUserPreferredOrg).await
  }

  "migration" should "delete organizations and associated data" in {
    val migration = createMigrationContainer(config)

    // User is in organization 1 & 2
    val otherUser = createUser()
    setPreferredOrg(
      migration.db,
      user = otherUser,
      organization = testOrganization2
    )

    // Only in organization 2
    val thirdUser =
      createUser(organization = Some(testOrganization2), datasets = List())
    setPreferredOrg(
      migration.db,
      user = thirdUser,
      organization = testOrganization2
    )

    // Preferred org should be set:
    userManager
      .get(otherUser.id)
      .await
      .value
      .preferredOrganizationId shouldBe (Some(testOrganization2.id))

    migration.scanAndDeleteAll()

    // Preferred org should fall back to testOrganization
    userManager
      .get(otherUser.id)
      .await
      .value
      .preferredOrganizationId shouldBe (Some(testOrganization.id))

    // If not part of other org, set to NULL
    userManager
      .get(thirdUser.id)
      .await
      .value
      .preferredOrganizationId shouldBe (None)

    // Organization should be gone
    organizationManager().get(testOrganization2.id).await.isLeft shouldBe true

    // Other organization should be untouched
    organizationManager().get(testOrganization.id).await.isRight shouldBe true

    // The dataset associated with testOrganization1 should still exist:
    datasetManager().get(testDataset.id).await.isRight shouldBe true
  }
}
