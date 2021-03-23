// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.managers

import cats.syntax.either._
import cats.instances.future._
import com.pennsieve.domain.NotFound
import com.pennsieve.models.{
  DBPermission,
  Feature,
  Organization,
  SystemTeamType
}
import com.pennsieve.test.helpers.TestDatabase
import com.pennsieve.test.helpers.EitherValue._
import org.scalatest.Matchers
import sun.awt.AWTAccessor.SystemTrayAccessor

import scala.concurrent.ExecutionContext.Implicits.global

class UserOrganizationManagerSpec
    extends BaseManagerSpec
    with TestDatabase
    with Matchers {

  implicit def um: UserManager = userManager

  val testOrgSchemaId = 111
  override def beforeEach(): Unit = {
    super.beforeEach()
    database.run(dropOrganizationSchema(testOrgSchemaId.toString)).await
  }

  "detecting schemas" should "detect existing schemas" in {
    val localOrgManager = new SecureOrganizationManager(database, superAdmin)

    database.run(createSchema(testOrgSchemaId.toString)).await

    val organization: Organization =
      Organization(id = testOrgSchemaId, name = "", slug = "", nodeId = "")

    assert(
      localOrgManager
        .schemaExists(organization)
        .await
        .value == testOrgSchemaId.toString
    )
    assert(
      localOrgManager
        .schemaExists(organization.copy(id = 11))
        .await == NotFound("schema").asLeft
    )
  }

  "adding a user to a organization" should "create a new membership row" in {
    val user = createUser()

    val users = organizationManager().getUsers(testOrganization).await.value
    users.map(_.id) should contain(user.id)

    val organizations: List[Organization] =
      userManager.getOrganizations(user).await.value
    organizations.head.id should be(testOrganization.id)

    organizationManager().removeUser(testOrganization, user).await.value

    val newOrganizations: List[Organization] =
      userManager.getOrganizations(user).await.value
    assert(newOrganizations.isEmpty)

    val newUsers = organizationManager().getUsers(testOrganization).await.value
    newUsers.map(_.id) should not contain user.id
  }

  "an organization with feature flags" should "see them when retrieved" in {
    val organization = organizationManager()
      .create(
        "TestOrg",
        "testorg",
        features = Set(Feature.ClinicalManagementFeature)
      )
      .await
      .value
    val createdFeatures =
      organizationManager().getActiveFeatureFlags(organization.id).await.value

    assert(
      createdFeatures.map(_.feature).contains(Feature.ClinicalManagementFeature)
    )
  }

  "an organization" should "have a publishers team created automatically" in {
    val organization = organizationManager()
      .create("pubs", "pubs", features = Set(Feature.ClinicalManagementFeature))
      .await
      .value

    val publishersTeam =
      organizationManager().getPublisherTeam(organization).await.value

    assert(publishersTeam._2.systemTeamType.contains(SystemTeamType.Publishers))
  }

  "a user can be found by name" should "when created" in {
    val user = createUser(email = "test@pennsieve.org")
    val gotUser = userManager.getByEmail(user.email).await
    assert(gotUser.isRight)
    assert(gotUser.value.email == "test@pennsieve.org")
  }

  "a user object" should "get extracted from a session they created" in {
    val user = createUser()

    val session = sessionManager.generateBrowserSession(user).await.value
    val gotUser = session.user().await.value
    assert(gotUser.id == user.id)
  }

  "a user with admin on an organization " should "be able to add people to the organization" in {

    val organizationOne = createOrganization()
    val organizationTwo = createOrganization()
    val organizationThree = createOrganization()

    val organizationOneDataset = createDataset(organizationOne)
    val organizationTwoDataset = createDataset(organizationTwo)
    val organizationThreeDataset = createDataset(organizationThree)

    val userOne = createUser(
      email = "userOne@test.com",
      organization = Some(organizationOne),
      permission = DBPermission.Administer,
      datasets = List(organizationOneDataset)
    )
    val userTwo = createUser(
      email = "userTwo@test.com",
      organization = Some(organizationTwo),
      permission = DBPermission.Administer,
      datasets = List(organizationTwoDataset)
    )
    val userThree = createUser(
      email = "userThree@test.com",
      organization = Some(organizationThree),
      permission = DBPermission.Administer,
      datasets = List(organizationThreeDataset)
    )

    val superUserOrganizationManager = organizationManager()

    val userTwoOrganizations = userManager.getOrganizations(userTwo).await.value
    assert(userTwoOrganizations == List(organizationTwo))

    val userThreeOrganizations =
      userManager.getOrganizations(userThree).await.value
    assert(userThreeOrganizations == List(organizationThree))

    val (organizationOneOwners, organizationOneAdmins) =
      superUserOrganizationManager
        .getOwnersAndAdministrators(organizationOne)
        .await
        .value
    organizationOneAdmins should contain(userOne)

    val (_, organizationTwoAdmins) = superUserOrganizationManager
      .getOwnersAndAdministrators(organizationTwo)
      .await
      .value
    organizationTwoAdmins should contain(userTwo)

    val (_, organizationThreeAdmins) = superUserOrganizationManager
      .getOwnersAndAdministrators(organizationThree)
      .await
      .value
    organizationThreeAdmins should contain(userThree)

    organizationOneAdmins should not contain userTwo
    organizationTwoAdmins should not contain userThree
    organizationThreeAdmins should not contain userOne

    val organizationManagerOne =
      new SecureOrganizationManager(database, userOne)
    val organizationManagerTwo =
      new SecureOrganizationManager(database, userTwo)
    val organizationManagerThree =
      new SecureOrganizationManager(database, userThree)

    // you can't add yourself to someone else's organization
    assert(
      organizationManagerOne
        .addUser(organizationThree, userOne, DBPermission.Delete)
        .await
        .isLeft
    )
    assert(
      organizationManagerTwo
        .addUser(organizationOne, userTwo, DBPermission.Delete)
        .await
        .isLeft
    )
    assert(
      organizationManagerThree
        .addUser(organizationOne, userThree, DBPermission.Delete)
        .await
        .isLeft
    )

    // you can add someone to the organization you manage
    assert(
      organizationManagerOne
        .addUser(organizationOne, userTwo, DBPermission.Delete)
        .await
        .isRight
    )
    assert(
      organizationManagerOne
        .addUser(organizationOne, userThree, DBPermission.Delete)
        .await
        .isRight
    )

    userManager
      .getOrganizations(userTwo)
      .await
      .value should contain theSameElementsAs List(
      organizationOne,
      organizationTwo
    )
    userManager
      .getOrganizations(userThree)
      .await
      .value should contain theSameElementsAs List(
      organizationOne,
      organizationThree
    )

    // owner permissions
    organizationOneOwners should not contain userThree
    organizationManagerOne
      .updateUserPermission(organizationOne, userThree, DBPermission.Owner)
      .await
      .value
    val (updatedOrgOneOwners, _) = superUserOrganizationManager
      .getOwnersAndAdministrators(organizationOne)
      .await
      .value
    updatedOrgOneOwners should contain(userThree)
  }

  "an organization with a non-unique slug" should "not be created" in {
    assert(
      organizationManager()
        .create("Test Organization", testOrganization.slug)
        .await
        .isLeft
    )
  }

  "removing a user from an organization" should "remove that user from the org's teams" in {
    val user = createUser()

    val users = organizationManager().getUsers(testOrganization).await.value
    users should contain(user)

    val newTeam1 = createTeam("newTeam1", testOrganization)
    val newTeam2 = createTeam("newTeam2", testOrganization)

    teamManager()
      .addUser(newTeam1, user, DBPermission.Collaborate)
      .await
      .value

    teamManager()
      .addUser(newTeam2, user, DBPermission.Collaborate)
      .await
      .value

    assert(teamManager().getUsers(newTeam1).await.value.contains(user))
    assert(teamManager().getUsers(newTeam2).await.value.contains(user))

    organizationManager().removeUser(testOrganization, user).await.value

    assert(!teamManager().getUsers(newTeam1).await.value.contains(user))
    assert(!teamManager().getUsers(newTeam2).await.value.contains(user))
    assert(
      !organizationManager()
        .getUsers(testOrganization)
        .await
        .value
        .contains(user)
    )
  }

}
