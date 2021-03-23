package com.pennsieve.test.helpers

import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.test.helpers.EitherValue._
import com.pennsieve.core.utilities.{
  OrganizationManagerContainer,
  PostgresDatabase,
  UserManagerContainer
}
import com.pennsieve.managers.{ SecureOrganizationManager, UserManager }
import com.pennsieve.models.DBPermission.{ Administer, Delete, Owner }
import com.pennsieve.models.Role.BlindReviewer
import com.pennsieve.models.SubscriptionStatus.ConfirmedSubscription
import com.pennsieve.models._

import scala.concurrent.ExecutionContext.Implicits.global

trait CoreSeed[
  SeedContainer <: OrganizationManagerContainer with UserManagerContainer
] extends TestDatabase {

  var userManager: UserManager = _
  var organizationManager: SecureOrganizationManager = _
  var db: Database = _
  var _db: PostgresDatabase = _

  var admin: User = _
  var nonAdmin: User = _
  var owner: User = _
  var blindReviewer: User = _

  var organizationOne: Organization = _
  var organizationTwo: Organization = _

  var schemas: Set[String] = Set()

  def createAndMigrateSchema(organizationId: Integer): Unit = {
    schemas = schemas + organizationId.toString
    db.run(createSchema(organizationId.toString)).await
    migrateOrganizationSchema(organizationId, _db)
  }

  def createOrganization(name: String, slug: String): Organization = {
    val organization: Organization =
      organizationManager.create(name, slug).await.value

    createAndMigrateSchema(organization.id)
    organization
  }

  def clearSchemas: DBIO[Unit] = {
    val queries = schemas.toList.map(dropOrganizationSchema)
    schemas = Set()
    DBIO.seq(queries: _*)
  }

  def seed(container: SeedContainer): Unit = {
    userManager = container.userManager
    db = container.db
    _db = container.postgresDatabase

    // Create Super Admin and Organizations
    admin = userManager
      .create(
        User(
          nodeId = NodeCodes.generateId(NodeCodes.userCode),
          email = "admin@pennsieve.org",
          firstName = "Adam",
          middleInitial = None,
          lastName = "Admin",
          degree = None,
          password = "password",
          credential = "fake-credentials",
          color = "",
          url = "",
          isSuperAdmin = true
        ),
        Some("password")
      )
      .await
      .value

    organizationManager = new TestableOrganizationManager(false, db, admin)

    organizationOne = createOrganization("Organization One", "organization_one")
    organizationTwo = createOrganization("Organization Two", "organization_two")

    // Update Admin and add to Organization
    admin = userManager
      .update(admin.copy(preferredOrganizationId = Some(organizationOne.id)))
      .await
      .value
    organizationManager.addUser(organizationOne, admin, Administer).await.value
    organizationManager.addUser(organizationTwo, admin, Administer).await.value

    // Create Non-Admin and add to Organizations
    nonAdmin = userManager
      .create(
        User(
          nodeId = NodeCodes.generateId(NodeCodes.userCode),
          email = "regular@company.com",
          firstName = "Regular",
          middleInitial = None,
          lastName = "User",
          degree = None,
          password = "password",
          credential = "fake-credentials",
          color = "",
          url = "",
          isSuperAdmin = false,
          preferredOrganizationId = Some(organizationTwo.id)
        ),
        Some("password")
      )
      .await
      .value

    organizationManager.addUser(organizationOne, nonAdmin, Delete).await.value
    organizationManager.addUser(organizationTwo, nonAdmin, Delete).await.value

    // Create Blind Reviewer and add to Organization
    blindReviewer = userManager
      .create(
        User(
          nodeId = NodeCodes.generateId(NodeCodes.userCode),
          email = "blindreviewer@blindplace.com",
          firstName = "Blind",
          middleInitial = None,
          lastName = "Reviewer",
          degree = None,
          password = "password",
          credential = "fake-credentials",
          color = "",
          url = "",
          isSuperAdmin = false,
          preferredOrganizationId = Some(organizationOne.id)
        ),
        Some("password")
      )
      .await
      .value

    organizationManager.addUser(
      organizationOne,
      blindReviewer,
      DBPermission.fromRole(Some(BlindReviewer))
    )

    // Create Owner and add to Organizations
    owner = userManager
      .create(
        User(
          nodeId = NodeCodes.generateId(NodeCodes.userCode),
          email = "owner@pennsieve.org",
          firstName = "owner",
          middleInitial = None,
          lastName = "owner",
          degree = None,
          password = "password",
          credential = "fake-credentials",
          color = "",
          url = "",
          isSuperAdmin = true
        ),
        Some("password")
      )
      .await
      .value

    organizationManager.addUser(organizationOne, owner, Owner).await.value
    organizationManager
      .updateSubscription(
        subscription = Subscription(
          organizationId = organizationOne.id,
          ConfirmedSubscription,
          `type` = None,
          acceptedBy = Some(owner.firstName),
          acceptedForOrganization = Some(organizationOne.name),
          acceptedByUser = Some(owner.id)
        )
      )
      .await
      .value
  }

}
