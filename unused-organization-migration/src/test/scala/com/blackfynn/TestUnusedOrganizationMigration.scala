package com.blackfynn.migrations.organizations

import com.blackfynn.core.utilities.DatabaseContainer
import com.blackfynn.db.UserMapper
import com.blackfynn.managers.BaseManagerSpec
import com.blackfynn.models.{ Organization, User }
import com.blackfynn.test.S3DockerContainer
import com.blackfynn.utilities.`unused-organization-migration`.UnusedOrganizationMigrationContainer
import com.typesafe.config.{ Config, ConfigFactory, ConfigValueFactory }
import org.scalatest._
import matchers._
import com.blackfynn.traits.PostgresProfile.api._

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

  var otherUser: User = _

  def setPreferredOrg(db: Database, user: User, organization: Organization) {
    val updateUserPreferredOrg = (for {
      u <- UserMapper if u.id === user.id
    } yield u.preferredOrganizationId).update(Some(organization.id))
    db.run(updateUserPreferredOrg).await
  }

  "migration" should "delete organizations and associated data" in {
    val migration = createMigrationContainer(config)
    otherUser = createUser()
    setPreferredOrg(
      migration.db,
      user = otherUser,
      organization = testOrganization2
    )
    // Preferred org should be set:
    userManager
      .get(otherUser.id)
      .await
      .right
      .get
      .preferredOrganizationId shouldBe (Some(testOrganization2.id))

    migration.scanAndDeleteAll()

    // Preferred org should be gone:
    userManager
      .get(otherUser.id)
      .await
      .right
      .get
      .preferredOrganizationId shouldBe (None)

    organizationManager().get(testOrganization2.id).await.isLeft shouldBe true

    // But testOrganization1 should still exist:
    userManager
      .get(otherUser.id)
      .await
      .right
      .get
      .preferredOrganizationId shouldBe (None)

    organizationManager().get(testOrganization.id).await.isRight shouldBe true

    // The dataset associated with testOrganization1 should still exist:
    datasetManager().get(testDataset.id).await.isRight shouldBe true
  }
}
