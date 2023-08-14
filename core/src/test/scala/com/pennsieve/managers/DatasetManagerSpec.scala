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

import cats.implicits._
import com.pennsieve.audit.middleware.TraceId
import com.pennsieve.managers.DatasetManager.{ OrderByColumn, OrderByDirection }
import com.pennsieve.models._
import org.scalatest.EitherValues._
import org.scalatest.matchers.should.Matchers._
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext.Implicits.global

class DatasetManagerSpec extends BaseManagerSpec {

  "a deleted dataset, recreated by the same name " should "be deletable again" in {

    val name = "TestDatasetName"

    val dm = datasetManager(testOrganization, superAdmin)

    val ds1 = dm.create(name).await.value
    val deleteResult1 = dm.delete(TraceId("ds1"), ds1).await
    assert(deleteResult1.isRight)

    val ds2 = dm.create(name).await.value
    val deleteResult2 = dm.delete(TraceId("ds2"), ds2).await
    assert(deleteResult2.isRight) // prior to fixing BF-245, this came back as  Left(ServiceError("name is already taken"))
  }

  "a dataset" should "never have its owner removed" in {

    val name = "TestDatasetName"

    val dm = datasetManager(testOrganization, superAdmin)
    val ds = dm.create(name).await.value

    val superAdmin2 = createSuperAdmin(email = "super2@pennsieve.org")

    val dm2 = datasetManager(testOrganization, superAdmin2)
    val owner = dm2.getOwner(ds).await.value

    val collabs: Collaborators = dm2.getCollaborators(ds).await.value
    val colaboratorIds = collabs.users.map(_.nodeId).toSet + owner.nodeId

    dm2.removeCollaborators(ds, colaboratorIds, false).await

    //The owner of a dataset should never be removed
    assert(dm.getOwner(ds).await.isRight)

  }

  "a dataset" should "provide a user's role" in {
    val user = createUser()
    val orgUser = createUser()
    val sharedUser = createUser()
    val teamUser = createUser()
    val externalUser =
      createUser(organization = Some(testOrganization2), datasets = List.empty)

    val team = createTeam("My team", testOrganization)
    teamManager(superAdmin).addUser(team, teamUser, DBPermission.Administer)

    val userDm = datasetManager(testOrganization, user)
    val dataset = userDm.create("My dataset").await.value

    userDm.maxRole(dataset, user).await.value should equal(Role.Owner)

    userDm.setOrganizationCollaboratorRole(dataset, Some(Role.Viewer)).await
    userDm.maxRole(dataset, orgUser).await.value should equal(Role.Viewer)

    userDm.addUserCollaborator(dataset, sharedUser, Role.Editor).await
    userDm.maxRole(dataset, sharedUser).await.value should equal(Role.Editor)

    userDm.addTeamCollaborator(dataset, team, Role.Manager).await
    userDm.maxRole(dataset, teamUser).await.value should equal(Role.Manager)

    // external user should not have any role
    val externalDm = datasetManager(testOrganization2, externalUser)
    externalDm.maxRole(dataset, externalUser).await.isLeft shouldBe true
  }

  "a dataset" should "be created to automatically process packages" in {
    val dm: DatasetManager = datasetManager()

    val dataset: Dataset =
      dm.create("My dataset", automaticallyProcessPackages = true).await.value

    dataset.automaticallyProcessPackages shouldBe true

  }

  "a dataset" should "be updated to automatically process packages" in {
    val dm: DatasetManager = datasetManager()

    val dataset: Dataset = dm.create("My dataset").await.value

    dataset.automaticallyProcessPackages shouldBe false

    val updated: Dataset =
      dm.update(dataset.copy(automaticallyProcessPackages = true)).await.value

    updated.automaticallyProcessPackages shouldBe true
  }

  "sourceFileCount" should "return the number of source files in the dataset" in {
    val dm: DatasetManager = datasetManager()

    val dataset: Dataset = createDataset()
    val pkg = createPackage(dataset = dataset)
    createFile(container = pkg)
    createFile(container = pkg)
    createFile(
      container = pkg,
      objectType = FileObjectType.View,
      processingState = FileProcessingState.NotProcessable
    )

    val count = dm.sourceFileCount(dataset).await.value

    assert(count == 2L)

  }

  "a user" should "be allowed to remove regular users" in {

    val name = "TestDatasetName"

    val dm = datasetManager(testOrganization, superAdmin)
    val ds = dm.create(name).await.value

    val orgUser = createUser()

    dm.addUserCollaborator(ds, orgUser, Role.Manager).await.value

    val collabs: Collaborators = dm.getCollaborators(ds).await.value

    assert(collabs.users.map(_.nodeId) contains orgUser.nodeId)

    dm.removeCollaborators(ds, Set(orgUser.nodeId), false).await

    val collabs2: Collaborators = dm.getCollaborators(ds).await.value

    assert(collabs2.users.map(_.nodeId).isEmpty)

  }

  "a user" should "not be allowed to remove integration users" in {

    val name = "TestDatasetName"

    val dm = datasetManager(testOrganization, superAdmin)
    val ds = dm.create(name).await.value

    dm.addUserCollaborator(ds, integrationUser, Role.Manager).await.value

    val collabs: Collaborators = dm.getCollaborators(ds).await.value

    assert(collabs.users.map(_.nodeId) contains integrationUser.nodeId)

    dm.removeCollaborators(ds, Set(integrationUser.nodeId), false).await

    val collabs2: Collaborators = dm.getCollaborators(ds).await.value

    assert(collabs2.users.map(_.nodeId) contains integrationUser.nodeId)

  }

  "a dataset" should "be text searchable by name" in {
    val dm: DatasetManager = datasetManager()

    createDataset(name = "The quick brown fox jumped over the lazy dogs")
    createDataset(name = "It was the best of times, it was the worst of times")
    createDataset(
      name = "How to teach your dog to sit, stay, jump, roll, and shake hands."
    )
    createDataset(name = "The Fantastic Mr. Fox")

    assert(
      dm.getDatasetPaginated(
          withRole = Role.Viewer,
          limit = None,
          offset = None,
          orderBy = (OrderByColumn.Name, OrderByDirection.Asc),
          status = None,
          textSearch = Some("foo")
        )
        .await
        .value
        ._1
        .size == 0
    )

    // Search should be case-insensitive
    assert(
      dm.getDatasetPaginated(
          withRole = Role.Viewer,
          limit = None,
          offset = None,
          orderBy = (OrderByColumn.Name, OrderByDirection.Asc),
          status = None,
          textSearch = Some("fox")
        )
        .await
        .value
        ._1
        .size == 2
    )

    // If a single word query is provided, make it a prefix-based search by default:
    assert(
      dm.getDatasetPaginated(
          withRole = Role.Viewer,
          limit = None,
          offset = None,
          orderBy = (OrderByColumn.Name, OrderByDirection.Asc),
          status = None,
          textSearch = Some("fantas")
        )
        .await
        .value
        ._1
        .size == 1 // match "fantastic"
    )

    // Search should also stem "dogs" -> "dog"
    assert(
      dm.getDatasetPaginated(
          withRole = Role.Viewer,
          limit = None,
          offset = None,
          orderBy = (OrderByColumn.Name, OrderByDirection.Asc),
          status = None,
          textSearch = Some("dog")
        )
        .await
        .value
        ._1
        .size == 2
    )

    assert(
      dm.getDatasetPaginated(
          withRole = Role.Viewer,
          limit = None,
          offset = None,
          orderBy = (OrderByColumn.Name, OrderByDirection.Asc),
          status = None,
          textSearch = Some("fox & jump") // "jumped" should be stemmed to "jump"
        )
        .await
        .value
        ._1
        .size == 1
    )

    assert(
      dm.getDatasetPaginated(
          withRole = Role.Viewer,
          limit = None,
          offset = None,
          orderBy = (OrderByColumn.Name, OrderByDirection.Asc),
          status = None,
          textSearch = Some("fox | jump") // "jumped" should be stemmed to "jump"
        )
        .await
        .value
        ._1
        .size == 3
    )

    assert(
      dm.getDatasetPaginated(
          withRole = Role.Viewer,
          limit = None,
          offset = None,
          orderBy = (OrderByColumn.Name, OrderByDirection.Asc),
          status = None,
          textSearch = Some("(fox | jump) & lazy")
        )
        .await
        .value
        ._1
        .size == 1
    )
  }

  "a dataset" should "be text searchable by summary" in {
    val dm: DatasetManager = datasetManager()

    createDataset(
      name = "The quick brown fox jumped over the lazy dogs",
      description = Some("foo, bar, baz, dogs")
    )
    createDataset(
      name = "It was the best of times, it was the worst of times",
      description = Some("this is a book about england")
    )
    createDataset(
      name = "How to teach your dog to sit, stay, jump, roll, and shake hands.",
      description = Some("your dog will thank you")
    )
    createDataset(
      name = "The Fantastic Mr. Fox",
      description = Some("this is a movie: baz quux")
    )

    assert(
      dm.getDatasetPaginated(
          withRole = Role.Viewer,
          limit = None,
          offset = None,
          orderBy = (OrderByColumn.Name, OrderByDirection.Asc),
          status = None,
          textSearch = Some("dog | england")
        )
        .await
        .value
        ._1
        .size == 3
    )

    assert(
      dm.getDatasetPaginated(
          withRole = Role.Viewer,
          limit = None,
          offset = None,
          orderBy = (OrderByColumn.Name, OrderByDirection.Asc),
          status = None,
          textSearch = Some("dog & england")
        )
        .await
        .value
        ._1
        .size == 0
    )
  }

  "a dataset" should "be text searchable by tags" in {
    val dm: DatasetManager = datasetManager()
    createDataset(name = "Dataset 1", tags = List("alpha", "beta", "gamma"))
    createDataset(name = "Dataset 2", tags = List("gamma", "delta", "omega"))
    createDataset(name = "Dataset 3", tags = List("alpha", "epsilon", "omega"))

    assert(
      dm.getDatasetPaginated(
          withRole = Role.Viewer,
          limit = None,
          offset = None,
          orderBy = (OrderByColumn.Name, OrderByDirection.Asc),
          status = None,
          textSearch = Some("gamma")
        )
        .await
        .value
        ._1
        .size == 2
    )

    assert(
      dm.getDatasetPaginated(
          withRole = Role.Viewer,
          limit = None,
          offset = None,
          orderBy = (OrderByColumn.Name, OrderByDirection.Asc),
          status = None,
          textSearch = Some("alpha")
        )
        .await
        .value
        ._1
        .size == 2
    )

    assert(
      dm.getDatasetPaginated(
          withRole = Role.Viewer,
          limit = None,
          offset = None,
          orderBy = (OrderByColumn.Name, OrderByDirection.Asc),
          status = None,
          textSearch = Some("delt:* | epsilon") // match a prefix
        )
        .await
        .value
        ._1
        .size == 2
    )

    assert(
      dm.getDatasetPaginated(
          withRole = Role.Viewer,
          limit = None,
          offset = None,
          orderBy = (OrderByColumn.Name, OrderByDirection.Asc),
          status = None,
          textSearch = Some("omega & delta")
        )
        .await
        .value
        ._1
        .size == 1
    )

    assert(
      dm.getDatasetPaginated(
          withRole = Role.Viewer,
          limit = None,
          offset = None,
          orderBy = (OrderByColumn.Name, OrderByDirection.Asc),
          status = None,
          textSearch = Some("omega & zeta")
        )
        .await
        .value
        ._1
        .size == 0
    )
  }

  "a dataset" should "be text searchable by owner/contributor" in {
    val dm: DatasetManager = datasetManager()

    val ds0 =
      createDataset(name = "Dataset 0", tags = List())
    val ds1 =
      createDataset(name = "Dataset 1", tags = List("alpha", "beta", "gamma"))
    val ds2 =
      createDataset(name = "Dataset 2", tags = List("gamma", "delta", "omega"))
    val ds3 = createDataset(
      name = "Dataset 3",
      tags = List("alpha", "epsilon", "omega")
    )

    val (c1, u1) =
      createContributor("John", "Smith", "john.smith@fake.com", Some("J"), None)
    val (c2, u2) = createContributor(
      "Ivan",
      "Ivanovich",
      "ivan@nyet.ru",
      Some("I"),
      Some(Degree.MD)
    )
    val (c3, u3) = createContributor(
      "Jane",
      "Public",
      "jane-public@aol.com",
      Some("H"),
      Some(Degree.PhD)
    )

    dm.addContributor(ds1, c1.id)
    dm.addContributor(ds2, c2.id)
    dm.addContributor(ds3, c3.id)

    assert(
      dm.getDatasetPaginated(
          withRole = Role.Viewer,
          limit = None,
          offset = None,
          orderBy = (OrderByColumn.Name, OrderByDirection.Asc),
          status = None,
          textSearch = Some("super | ivanovich") // dataset creator = "SUPER ADMIN"
        )
        .await
        .value
        ._1
        .size == 5 // admin dataset + Dataset 0-4, as super admin is the creator of all datasets
    )

    assert(
      dm.getDatasetPaginated(
          withRole = Role.Viewer,
          limit = None,
          offset = None,
          orderBy = (OrderByColumn.Name, OrderByDirection.Asc),
          status = None,
          textSearch = Some("super & ivanovich") // dataset creator = "SUPER ADMIN"
        )
        .await
        .value
        ._1
        .size == 1
    )

    assert(
      dm.getDatasetPaginated(
          withRole = Role.Viewer,
          limit = None,
          offset = None,
          orderBy = (OrderByColumn.Name, OrderByDirection.Asc),
          status = None,
          textSearch = Some("suber & ivanovich") // misspell dataset creator
        )
        .await
        .value
        ._1
        .size == 0
    )
  }

  "a dataset" should "be searchable with multi-term queries" in {
    val dm: DatasetManager = datasetManager()

    createDataset(
      name = "The quick brown fox jumped over the lazy dogs",
      tags = List("alpha", "omega")
    )
    createDataset(
      name = "It was the best of times, it was the worst of times",
      tags = List("beta", "delta")
    )
    createDataset(
      name = "How to teach your dog to sit, stay, jump, roll, and shake hands."
    )
    createDataset(name = "The Fantastic Mr. Fox", tags = List("delta"))

    assert(
      dm.getDatasetPaginated(
          withRole = Role.Viewer,
          limit = None,
          offset = None,
          orderBy = (OrderByColumn.Name, OrderByDirection.Asc),
          status = None,
          textSearch = Some("fox jump")
        )
        .await
        .value
        ._1
        .size == 1
    )

    assert(
      dm.getDatasetPaginated(
          withRole = Role.Viewer,
          limit = None,
          offset = None,
          orderBy = (OrderByColumn.Name, OrderByDirection.Asc),
          status = None,
          textSearch = Some("fox jump alpha")
        )
        .await
        .value
        ._1
        .size == 1
    )

    assert(
      dm.getDatasetPaginated(
          withRole = Role.Viewer,
          limit = None,
          offset = None,
          orderBy = (OrderByColumn.Name, OrderByDirection.Asc),
          status = None,
          textSearch = Some("fox jump beta")
        )
        .await
        .value
        ._1
        .size == 0
    )
  }

  def isLocked(dataset: Dataset, user: User): Boolean =
    datasetManager(testOrganization, user).isLocked(dataset).await.value

  def isEditable(dataset: Dataset, user: User): Boolean =
    datasetManager(testOrganization, user).isEditable(dataset).await.value

  "a dataset" should "not be locked for publishers when under review" in {

    val dm = datasetManager(testOrganization, superAdmin)
    val dataset = createDataset()

    // The dataset is currenly shared with the publisher team

    val publisherUser = createUser()
    val publisherTeam = organizationManager()
      .getPublisherTeam(testOrganization)
      .map(_._1)
      .await
      .value
    teamManager()
      .addUser(publisherTeam, publisherUser, DBPermission.Administer)
      .await
      .value
    dm.addTeamCollaborator(dataset, publisherTeam, Role.Editor).await.value

    // And with one other team and user for good measure

    val otherTeamUser = createUser()
    val otherTeam = createTeam("Other team", testOrganization)
    teamManager(superAdmin).addUser(
      otherTeam,
      otherTeamUser,
      DBPermission.Administer
    )
    dm.addTeamCollaborator(dataset, otherTeam, Role.Manager).await.value

    // Is not locked for publishers while pending review

    datasetPublicationStatusManager()
      .create(dataset, PublicationStatus.Requested, PublicationType.Publication)
      .await
      .value

    assert(!isLocked(dataset, publisherUser))
    assert(isLocked(dataset, superAdmin))
    assert(isLocked(dataset, otherTeamUser))

    // Is locked for everyone while accepted for publication

    datasetPublicationStatusManager()
      .create(dataset, PublicationStatus.Accepted, PublicationType.Publication)
      .await
      .value

    assert(isLocked(dataset, publisherUser))
    assert(isLocked(dataset, superAdmin))
    assert(isLocked(dataset, otherTeamUser))

    // Is not locked for publishers when publication failed

    datasetPublicationStatusManager()
      .create(dataset, PublicationStatus.Failed, PublicationType.Publication)
      .await
      .value

    assert(!isLocked(dataset, publisherUser))
    assert(isLocked(dataset, superAdmin))
    assert(isLocked(dataset, otherTeamUser))
  }

  "a dataset" should "should be editable for publishers when under review" in {
    val dataset = createDataset()

    val publisherUser = createUser()
    val publisherTeam = organizationManager()
      .getPublisherTeam(testOrganization)
      .map(_._1)
      .await
      .value
    teamManager()
      .addUser(publisherTeam, publisherUser, DBPermission.Administer)
      .await
      .value

    datasetPublicationStatusManager()
      .create(dataset, PublicationStatus.Requested, PublicationType.Publication)
      .await
      .value

    assert(isEditable(dataset, publisherUser))
    assert(!isEditable(dataset, superAdmin))
  }

  "a dataset" should "should not be editable for publishers when NOT under review" in {
    val dataset = createDataset()

    val publisherUser = createUser()
    val publisherTeam = organizationManager()
      .getPublisherTeam(testOrganization)
      .map(_._1)
      .await
      .value
    teamManager()
      .addUser(publisherTeam, publisherUser, DBPermission.Administer)
      .await
      .value

    datasetPublicationStatusManager()
      .create(dataset, PublicationStatus.Completed, PublicationType.Publication)
      .await
      .value

    assert(!isEditable(dataset, publisherUser))
    assert(!isEditable(dataset, superAdmin))
  }

  "a dataset" should "should NOT be editable for non-publishers when under review" in {
    val dm = datasetManager(testOrganization, superAdmin)
    val dataset = createDataset()

    val someOtherUser = createUser()
    val otherTeam = createTeam("Other team", testOrganization)
    teamManager(superAdmin).addUser(
      otherTeam,
      someOtherUser,
      DBPermission.Administer
    )
    dm.addTeamCollaborator(dataset, otherTeam, Role.Manager).await.value

    datasetPublicationStatusManager()
      .create(dataset, PublicationStatus.Requested, PublicationType.Publication)
      .await
      .value

    assert(!isEditable(dataset, someOtherUser))
  }

  "enableWebhook" should "create a DatasetIntegration for the given dataset and webhook" in {
    val user = createUser()
    val dataset = createDataset(user = user)
    val (webhook, _) = createWebhook(creatingUser = user)
    val dm = datasetManager(user = user)

    val result = dm.enableWebhook(dataset, webhook, integrationUser).await
    assert(result.isRight)
    val returned = result.value
    assert(returned.datasetId == dataset.id)
    assert(returned.webhookId == webhook.id)
    assert(returned.enabledBy == user.id)

    val actualIntegrations = database
      .run(
        dm.datasetIntegrationsMapper
          .filter(_.datasetId === dataset.id)
          .result
      )
      .mapTo[Seq[DatasetIntegration]]
      .await
    assert(actualIntegrations.length == 1)

    val actual = actualIntegrations.head
    assert(actual.equals(returned))
  }

  "enableWebhook" should "should be idempotent" in {
    val user = createUser()
    val dataset = createDataset(user = user)
    val (webhook, _) = createWebhook(creatingUser = user)
    val dm = datasetManager(user = user)

    val firstResult =
      dm.enableWebhook(dataset, webhook, integrationUser).await.value
    val secondResultEither =
      dm.enableWebhook(dataset, webhook, integrationUser).await
    assert(secondResultEither.isRight)
    val secondResult = secondResultEither.value

    assert(firstResult.equals(secondResult))

    val actualIntegrations = database
      .run(
        dm.datasetIntegrationsMapper
          .filter(_.datasetId === dataset.id)
          .result
      )
      .mapTo[Seq[DatasetIntegration]]
      .await
    assert(actualIntegrations.length == 1)

    val actual = actualIntegrations.head
    assert(actual.equals(firstResult))
  }

  "enableWebhook" should "should ignore redundant invocations by a second user" in {
    val user1 = createUser()
    val dataset = createDataset(user = user1)
    val (webhook, _) = createWebhook(creatingUser = user1)
    val dm1 = datasetManager(user = user1)

    val firstResult =
      dm1.enableWebhook(dataset, webhook, integrationUser).await.value

    val user2 = createUser()
    val dm2 = datasetManager(user = user2)
    val secondResultEither =
      dm2.enableWebhook(dataset, webhook, integrationUser).await
    assert(secondResultEither.isRight)
    val secondResult = secondResultEither.value

    assert(firstResult.equals(secondResult))

    val actualIntegrations = database
      .run(
        dm2.datasetIntegrationsMapper
          .filter(_.datasetId === dataset.id)
          .result
      )
      .mapTo[Seq[DatasetIntegration]]
      .await
    assert(actualIntegrations.length == 1)

    val actual = actualIntegrations.head
    assert(actual.equals(firstResult))
  }

  "disableWebhook" should "delete the DatasetIntegration for the given dataset and webhook" in {
    val user = createUser()
    val dataset = createDataset(user = user)
    val (webhook, _) = createWebhook(creatingUser = user)
    val dm = datasetManager(user = user)

    dm.enableWebhook(dataset, webhook, integrationUser).await

    val result = dm.disableWebhook(dataset, webhook, integrationUser).await
    assert(result.isRight)
    val deletedRowCount = result.value
    assert(deletedRowCount == 1)

    val actualIntegrations = database
      .run(
        dm.datasetIntegrationsMapper
          .filter(_.datasetId === dataset.id)
          .result
      )
      .mapTo[Seq[DatasetIntegration]]
      .await
    assert(actualIntegrations.isEmpty)
  }

  "disableWebhook" should "return 0 if the given webhook is not enabled for the given dataset" in {
    val user = createUser()
    val dataset = createDataset(user = user)
    val (webhook1, _) = createWebhook(creatingUser = user)
    val (webhook2, _) =
      createWebhook(description = "Test webhook 2", creatingUser = user)

    val dm = datasetManager(user = user)

    val enabledWebhook =
      dm.enableWebhook(dataset, webhook2, integrationUser).await.value

    val result = dm.disableWebhook(dataset, webhook1, integrationUser).await
    assert(result.isRight)

    val deletedRowCount = result.value
    assert(deletedRowCount == 0)

    val actualIntegrations = database
      .run(
        dm.datasetIntegrationsMapper
          .filter(_.datasetId === dataset.id)
          .result
      )
      .mapTo[Seq[DatasetIntegration]]
      .await
    assert(actualIntegrations.length == 1)

    val actual = actualIntegrations.head
    assert(actual.equals(enabledWebhook))
  }

  "enableDefaultWebhooks" should "create the default dataset integrations" in {
    val user = createUser()
    val dataset = createDataset(user = user)
    val (defaultWebhook, _) =
      createWebhook(description = "default webhook", isDefault = true)
    createWebhook(description = "not default")

    val dm = datasetManager(user = user)

    val result = dm.enableDefaultWebhooks(dataset).await
    assert(result.isRight)
    val enabledCountOpt = result.value
    assert(enabledCountOpt.isDefined)
    assert(enabledCountOpt.get == 1)

    val actualIntegrations = database
      .run(
        dm.datasetIntegrationsMapper
          .filter(_.datasetId === dataset.id)
          .result
      )
      .mapTo[Seq[DatasetIntegration]]
      .await
    assert(actualIntegrations.length == 1)

    val actual = actualIntegrations.head
    assert(actual.datasetId.equals(dataset.id))
    assert(actual.webhookId.equals(defaultWebhook.id))
    assert(actual.enabledBy.equals(user.id))
  }

  it should "create the default dataset integrations for any authorized user" in {
    val webhookCreator = createUser()
    val (defaultWebhook, _) =
      createWebhook(
        creatingUser = webhookCreator,
        description = "default webhook",
        isDefault = true
      )

    val user = createUser()
    val dataset = createDataset(user = user)

    val dm = datasetManager(user = user)

    val result = dm.enableDefaultWebhooks(dataset).await
    assert(result.isRight)
    val enabledCountOpt = result.value
    assert(enabledCountOpt.isDefined)
    assert(enabledCountOpt.get == 1)

    val actualIntegrations = database
      .run(
        dm.datasetIntegrationsMapper
          .filter(_.datasetId === dataset.id)
          .result
      )
      .mapTo[Seq[DatasetIntegration]]
      .await
    assert(actualIntegrations.length == 1)

    val actual = actualIntegrations.head
    assert(actual.datasetId.equals(dataset.id))
    assert(actual.webhookId.equals(defaultWebhook.id))
    assert(actual.enabledBy.equals(user.id))
  }

  it should "create the default and requested dataset integrations" in {
    val user = createUser()
    val dataset = createDataset(user = user)
    val (defaultWebhook, _) =
      createWebhook(description = "default webhook", isDefault = true)
    val (requestedWebhook, _) =
      createWebhook(description = "requested but not default")

    val dm = datasetManager(user = user)

    val result = dm
      .enableDefaultWebhooks(dataset, Some(Set(requestedWebhook.id)), None)
      .await
    assert(result.isRight)
    val enabledCountOpt = result.value
    assert(enabledCountOpt.isDefined)
    assert(enabledCountOpt.get == 2)

    val actualIntegrations = database
      .run(
        dm.datasetIntegrationsMapper
          .getByDatasetId(dataset.id)
          .result
      )
      .await

    assert(actualIntegrations.length == 2)
    val actualDefault =
      actualIntegrations.filter(_.webhookId == defaultWebhook.id)
    assert(actualDefault.size == 1)
    assert(actualDefault.head.datasetId.equals(dataset.id))
    assert(actualDefault.head.enabledBy.equals(user.id))

    val actualRequested =
      actualIntegrations.filter(_.webhookId == requestedWebhook.id)
    assert(actualRequested.size == 1)
    assert(actualRequested.head.datasetId.equals(dataset.id))
    assert(actualRequested.head.enabledBy.equals(user.id))

  }

  it should "create the default and requested dataset integrations if the requested webhook is private and created by the requesting user" in {
    val user = createUser()
    val dataset = createDataset(user = user)
    val (defaultWebhook, _) =
      createWebhook(description = "default webhook", isDefault = true)
    val (requestedWebhook, _) =
      createWebhook(
        creatingUser = user,
        description = "requested but not default",
        isPrivate = true
      )

    val dm = datasetManager(user = user)

    val result = dm
      .enableDefaultWebhooks(
        dataset,
        includedWebhookIds = Some(Set(requestedWebhook.id))
      )
      .await
    assert(result.isRight)
    val enabledCountOpt = result.value
    assert(enabledCountOpt.isDefined)
    assert(enabledCountOpt.get == 2)

    val actualIntegrations = database
      .run(
        dm.datasetIntegrationsMapper
          .getByDatasetId(dataset.id)
          .result
      )
      .await

    assert(actualIntegrations.length == 2)
    val actualDefault =
      actualIntegrations.filter(_.webhookId == defaultWebhook.id)
    assert(actualDefault.size == 1)
    assert(actualDefault.head.datasetId.equals(dataset.id))
    assert(actualDefault.head.enabledBy.equals(user.id))

    val actualRequested =
      actualIntegrations.filter(_.webhookId == requestedWebhook.id)
    assert(actualRequested.size == 1)
    assert(actualRequested.head.datasetId.equals(dataset.id))
    assert(actualRequested.head.enabledBy.equals(user.id))

  }

  it should "create the default and requested dataset integrations if the requested webhook is private and requesting user is super admin" in {
    val dataset = createDataset(user = superAdmin)
    val (defaultWebhook, _) =
      createWebhook(description = "default webhook", isDefault = true)

    val privateWebhookCreator = createUser()

    val (requestedWebhook, _) =
      createWebhook(
        creatingUser = privateWebhookCreator,
        description = "requested but not default",
        isPrivate = true
      )

    val dm = datasetManager(user = superAdmin)

    val result = dm
      .enableDefaultWebhooks(
        dataset,
        includedWebhookIds = Some(Set(requestedWebhook.id))
      )
      .await
    assert(result.isRight)
    val enabledCountOpt = result.value
    assert(enabledCountOpt.isDefined)
    assert(enabledCountOpt.get == 2)

    val actualIntegrations = database
      .run(
        dm.datasetIntegrationsMapper
          .getByDatasetId(dataset.id)
          .result
      )
      .await

    assert(actualIntegrations.length == 2)
    val actualDefault =
      actualIntegrations.filter(_.webhookId == defaultWebhook.id)
    assert(actualDefault.size == 1)
    assert(actualDefault.head.datasetId.equals(dataset.id))
    assert(actualDefault.head.enabledBy.equals(superAdmin.id))

    val actualRequested =
      actualIntegrations.filter(_.webhookId == requestedWebhook.id)
    assert(actualRequested.size == 1)
    assert(actualRequested.head.datasetId.equals(dataset.id))
    assert(actualRequested.head.enabledBy.equals(superAdmin.id))

  }

  it should "not create any excluded default dataset integrations" in {
    val user = createUser()
    val dataset = createDataset(user = user)
    val (defaultWebhook, _) =
      createWebhook(description = "default webhook", isDefault = true)
    val (defaultExcludedWebhook, _) =
      createWebhook(description = "default but excluded", isDefault = true)

    val dm = datasetManager(user = user)

    val result = dm
      .enableDefaultWebhooks(
        dataset,
        None,
        Some(Set(defaultExcludedWebhook.id))
      )
      .await
    assert(result.isRight)
    val enabledCountOpt = result.value
    assert(enabledCountOpt.isDefined)
    assert(enabledCountOpt.get == 1)

    val actualIntegrations = database
      .run(
        dm.datasetIntegrationsMapper
          .getByDatasetId(dataset.id)
          .result
      )
      .await

    assert(actualIntegrations.length == 1)

    val actualIntegration =
      actualIntegrations.head
    assert(actualIntegration.webhookId.equals(defaultWebhook.id))
    assert(actualIntegration.datasetId.equals(dataset.id))
    assert(actualIntegration.enabledBy.equals(user.id))

  }

  it should "not create any requested dataset integrations for private webhooks not created by user" in {
    val webhookCreator = createUser()
    val (privateWebhook, _) = createWebhook(
      creatingUser = webhookCreator,
      description = "private webhook",
      isPrivate = true
    )
    val (defaultWebhook, _) =
      createWebhook(description = "default webhook", isDefault = true)
    val user = createUser()
    val dataset = createDataset(user = user)

    val dm = datasetManager(user = user)

    val result = dm
      .enableDefaultWebhooks(
        dataset,
        includedWebhookIds = Some(Set(privateWebhook.id))
      )
      .await
    assert(result.isRight)
    val enabledCountOpt = result.value
    assert(enabledCountOpt.isDefined)
    assert(enabledCountOpt.get == 1)

    val actualIntegrations = database
      .run(
        dm.datasetIntegrationsMapper
          .getByDatasetId(dataset.id)
          .result
      )
      .await

    assert(actualIntegrations.length == 1)

    val actualIntegration =
      actualIntegrations.head
    assert(actualIntegration.webhookId.equals(defaultWebhook.id))
    assert(actualIntegration.datasetId.equals(dataset.id))
    assert(actualIntegration.enabledBy.equals(user.id))

  }

  it should "create the requested dataset integrations and not any excluded defaults" in {
    val user = createUser()
    val dataset = createDataset(user = user)
    val (defaultWebhook, _) =
      createWebhook(description = "default webhook", isDefault = true)
    val (requestedWebhook, _) =
      createWebhook(description = "requested but not default")

    val dm = datasetManager(user = user)

    val result = dm
      .enableDefaultWebhooks(
        dataset,
        Some(Set(requestedWebhook.id)),
        Some(Set(defaultWebhook.id))
      )
      .await
    assert(result.isRight)
    val enabledCountOpt = result.value
    assert(enabledCountOpt.isDefined)
    assert(enabledCountOpt.get == 1)

    val actualIntegrations = database
      .run(
        dm.datasetIntegrationsMapper
          .getByDatasetId(dataset.id)
          .result
      )
      .await

    assert(actualIntegrations.length == 1)

    val actualRequested =
      actualIntegrations.head
    assert(actualRequested.webhookId.equals(requestedWebhook.id))
    assert(actualRequested.datasetId.equals(dataset.id))
    assert(actualRequested.enabledBy.equals(user.id))

  }

  it should "work when there are no default dataset integrations" in {
    val user = createUser()
    val dataset = createDataset(user = user)

    val dm = datasetManager(user = user)

    val result = dm.enableDefaultWebhooks(dataset).await
    assert(result.isRight)
    val enabledCountOpt = result.value
    assert(enabledCountOpt.isDefined)
    assert(enabledCountOpt.get == 0)

    val actualIntegrations = database
      .run(
        dm.datasetIntegrationsMapper
          .getByDatasetId(dataset.id)
          .result
      )
      .await

    assert(actualIntegrations.isEmpty)

  }

  it should "work when all default dataset integrations are excluded" in {
    val user = createUser()
    val dataset = createDataset(user = user)
    val (defaultWebhook, _) = createWebhook(isDefault = true)

    val dm = datasetManager(user = user)

    val result = dm
      .enableDefaultWebhooks(
        dataset,
        excludedWebhookIds = Some(Set(defaultWebhook.id))
      )
      .await
    assert(result.isRight)
    val enabledCountOpt = result.value
    assert(enabledCountOpt.isDefined)
    assert(enabledCountOpt.get == 0)

    val actualIntegrations = database
      .run(
        dm.datasetIntegrationsMapper
          .getByDatasetId(dataset.id)
          .result
      )
      .await

    assert(actualIntegrations.isEmpty)

  }

  it should "work when default dataset integrations are included and excluded" in {
    val user = createUser()
    val dataset = createDataset(user = user)
    val (defaultWebhook, _) = createWebhook(isDefault = true)

    val dm = datasetManager(user = user)

    val result = dm
      .enableDefaultWebhooks(
        dataset,
        includedWebhookIds = Some(Set(defaultWebhook.id)),
        excludedWebhookIds = Some(Set(defaultWebhook.id))
      )
      .await
    assert(result.isRight)
    val enabledCountOpt = result.value
    assert(enabledCountOpt.isDefined)
    assert(enabledCountOpt.get == 0)

    val actualIntegrations = database
      .run(
        dm.datasetIntegrationsMapper
          .getByDatasetId(dataset.id)
          .result
      )
      .await

    assert(actualIntegrations.isEmpty)

  }

}
