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

import cats.data._
import cats.implicits._
import com.pennsieve.audit.middleware.TraceId
import com.pennsieve.domain.CoreError
import com.pennsieve.managers.DatasetManager.{ OrderByColumn, OrderByDirection }
import com.pennsieve.models._
import com.pennsieve.test.helpers.EitherValue._
import org.scalatest.OptionValues._
import org.scalatest.Matchers._
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

    dm2.removeCollaborators(ds, colaboratorIds).await

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

    val count = dm.sourceFileCount(dataset).await.right.get

    assert(count == 2L)

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
        .right
        .get
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
        .right
        .get
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
        .right
        .get
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
        .right
        .get
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
        .right
        .get
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
        .right
        .get
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
        .right
        .get
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
        .right
        .get
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
        .right
        .get
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
        .right
        .get
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
        .right
        .get
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
        .right
        .get
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
        .right
        .get
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
        .right
        .get
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
        .right
        .get
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
        .right
        .get
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
        .right
        .get
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
        .right
        .get
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
        .right
        .get
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
        .right
        .get
        ._1
        .size == 0
    )
  }

  def isLocked(dataset: Dataset, user: User): Boolean =
    datasetManager(testOrganization, user).isLocked(dataset).await.right.get

  "a dataset" should "not be locked for publishers when under review" in {

    val dm = datasetManager(testOrganization, superAdmin)
    val dataset = createDataset()

    // The dataset is currenly shared with the publisher team

    val publisherUser = createUser()
    val publisherTeam = organizationManager()
      .getPublisherTeam(testOrganization)
      .map(_._1)
      .await
      .right
      .get
    teamManager()
      .addUser(publisherTeam, publisherUser, DBPermission.Administer)
      .await
      .right
      .get
    dm.addTeamCollaborator(dataset, publisherTeam, Role.Editor).await.right.get

    // And with one other team and user for good measure

    val otherTeamUser = createUser()
    val otherTeam = createTeam("Other team", testOrganization)
    teamManager(superAdmin).addUser(
      otherTeam,
      otherTeamUser,
      DBPermission.Administer
    )
    dm.addTeamCollaborator(dataset, otherTeam, Role.Manager).await.right.get

    // Is not locked for publishers while pending review

    datasetPublicationStatusManager()
      .create(dataset, PublicationStatus.Requested, PublicationType.Publication)
      .await
      .right
      .get

    assert(!isLocked(dataset, publisherUser))
    assert(isLocked(dataset, superAdmin))
    assert(isLocked(dataset, otherTeamUser))

    // Is locked for everyone while accepted for publication

    datasetPublicationStatusManager()
      .create(dataset, PublicationStatus.Accepted, PublicationType.Publication)
      .await
      .right
      .get

    assert(isLocked(dataset, publisherUser))
    assert(isLocked(dataset, superAdmin))
    assert(isLocked(dataset, otherTeamUser))

    // Is not locked for publishers when publication failed

    datasetPublicationStatusManager()
      .create(dataset, PublicationStatus.Failed, PublicationType.Publication)
      .await
      .right
      .get

    assert(!isLocked(dataset, publisherUser))
    assert(isLocked(dataset, superAdmin))
    assert(isLocked(dataset, otherTeamUser))
  }

  "enableWebhook" should "create a DatasetIntegration for the given dataset and webhook" in {
    val user = createUser()
    val dataset = createDataset(user = user)
    val (webhook, _) = createWebhook(creatingUser = user)
    val dm = datasetManager(user = user)

    val result = dm.enableWebhook(dataset, webhook).await
    assert(result.isRight)
    val returned = result.right.get
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

    val actual = actualIntegrations(0)
    assert(actual.equals(returned))
  }

  "enableWebhook" should "should be idempotent" in {
    val user = createUser()
    val dataset = createDataset(user = user)
    val (webhook, _) = createWebhook(creatingUser = user)
    val dm = datasetManager(user = user)

    val firstResult = dm.enableWebhook(dataset, webhook).await.right.get
    val secondResultEither = dm.enableWebhook(dataset, webhook).await
    assert(secondResultEither.isRight)
    val secondResult = secondResultEither.right.get

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

    val actual = actualIntegrations(0)
    assert(actual.equals(firstResult))
  }

  "enableWebhook" should "should ignore redundant invocations by a second user" in {
    val user1 = createUser()
    val dataset = createDataset(user = user1)
    val (webhook, _) = createWebhook(creatingUser = user1)
    val dm1 = datasetManager(user = user1)

    val firstResult = dm1.enableWebhook(dataset, webhook).await.right.get

    val user2 = createUser()
    val dm2 = datasetManager(user = user2)
    val secondResultEither = dm2.enableWebhook(dataset, webhook).await
    assert(secondResultEither.isRight)
    val secondResult = secondResultEither.right.get

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

    val actual = actualIntegrations(0)
    assert(actual.equals(firstResult))
  }
}
