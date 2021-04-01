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

package com.pennsieve.authorization.routes

import akka.http.javadsl.model.HttpRequest
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.HttpHeader
import akka.http.scaladsl.model.headers.`Content-Length`
import akka.http.scaladsl.model.HttpMethods.{ GET, PUT }
import akka.http.scaladsl.model.StatusCodes.{
  Forbidden,
  NotFound,
  OK,
  Unauthorized
}
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream._
import akka.testkit.TestKitBase
import cats.data._
import cats.implicits._
import com.pennsieve.akka.http.EitherValue._
import com.pennsieve.aws.cognito.MockJwkProvider
import com.pennsieve.auth.middleware.{
  DatasetId,
  DatasetNodeId,
  EncryptionKeyId,
  Jwt,
  OrganizationId,
  Permission,
  UserClaim
}
import com.pennsieve.auth.middleware.Jwt.DatasetRole
import com.pennsieve.aws.cognito.MockCognito
import com.pennsieve.db.{
  ChangelogEventMapper,
  ContributorMapper,
  DatasetPublicationStatusMapper,
  DatasetsMapper
}
import com.pennsieve.domain.Sessions.APISession
import com.pennsieve.dtos.UserDTO
import com.pennsieve.managers.{
  ContributorManager,
  DatasetManager,
  DatasetPreviewManager,
  DatasetPublicationStatusManager,
  TeamManager,
  UserManager
}
import com.pennsieve.models.{
  DBPermission,
  DatasetPreviewer,
  EmbargoAccess,
  Feature,
  FeatureFlag,
  Organization,
  OrganizationNodeId,
  PackageState,
  PublicationStatus,
  PublicationType,
  Role,
  User
}
import com.pennsieve.traits.PostgresProfile.api._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import java.time.Instant
import java.util.UUID

import com.pennsieve.auth.middleware.Jwt.Role.RoleIdentifier
import shapeless._

import scala.collection.immutable.{ Seq => ImmutableSeq }
import scala.concurrent.duration._
import scala.concurrent._

class AuthorizationRoutesSpec
    extends AuthorizationServiceSpec
    with TestKitBase {

  val mockCognito: MockCognito = new MockCognito()

  def withXOriginalURI(uri: String): ImmutableSeq[HttpHeader] =
    ImmutableSeq(RawHeader("X-Original-URI", uri))

  "GET /authorization route" should {

    "return a JWT for an authorized user" in {

      testRequest(
        GET,
        "/authorization",
        session = nonAdminCognitoJwt,
        headers = withXOriginalURI("/model-schema")
      ) ~>
        routes ~> check {
        status shouldEqual OK

        val claim: Jwt.Claim = getClaim()

        claim.content shouldBe a[UserClaim]
        claim.content.roles should have length 1
      }
    }

    "return a JWT for an authorized user with a dataset claim" in {
      val datasetsMapper = new DatasetsMapper(organizationTwo)
      val datasetManager = new DatasetManager(db, nonAdmin, datasetsMapper)
      val dataset = datasetManager.create("Test Dataset").await.value

      testRequest(
        GET,
        s"/authorization?dataset_id=${dataset.id}",
        session = nonAdminCognitoJwt,
        headers = withXOriginalURI(s"/model-schema/dataset/${dataset.id}")
      ) ~>
        routes ~> check {
        status shouldEqual OK

        val claim: Jwt.Claim = getClaim()

        claim.content shouldBe a[UserClaim]
        claim.content.roles should have length 2
      }
    }

    "return a JWT for resolving a valid dataset by node ID" in {
      val datasetsMapper = new DatasetsMapper(organizationTwo)
      val datasetManager = new DatasetManager(db, nonAdmin, datasetsMapper)
      val dataset = datasetManager
        .create("Test Dataset (from node ID)")
        .await
        .value

      testRequest(
        GET,
        s"/authorization?dataset_id=${dataset.nodeId}",
        session = nonAdminCognitoJwt,
        headers = withXOriginalURI(s"/model-schema/dataset/${dataset.nodeId}")
      ) ~>
        routes ~> check {
        status shouldEqual OK

        val claim: Jwt.Claim = getClaim()

        claim.content shouldBe a[UserClaim]
        claim.content.roles should have length 2
        claim.content.roles.collect {
          case Jwt.DatasetRole(
              Inl(datasetId),
              _,
              Some(DatasetNodeId(dataset.nodeId)),
              _
              ) =>
            datasetId
        } should have length 1
      }
    }

    "return a JWT with locked = true for datasets with the right publication status" in {
      val datasetsMapper = new DatasetsMapper(organizationTwo)
      val datasetManager = new DatasetManager(db, nonAdmin, datasetsMapper)
      val datasetPublicationStatusManager = new DatasetPublicationStatusManager(
        db,
        nonAdmin,
        new DatasetPublicationStatusMapper(organizationTwo),
        new ChangelogEventMapper(organizationTwo)
      )

      val dataset = datasetManager
        .create("Test Dataset (from node ID)")
        .await
        .value

      testRequest(
        GET,
        s"/authorization?dataset_id=${dataset.nodeId}",
        session = nonAdminCognitoJwt,
        headers = withXOriginalURI(s"/model-schema/dataset/${dataset.nodeId}")
      ) ~>
        routes ~> check {

        val claim: Jwt.Claim = getClaim()
        claim.content.roles.collect {
          case Jwt.DatasetRole(
              Inl(datasetId),
              _,
              Some(DatasetNodeId(dataset.nodeId)),
              Some(false)
              ) =>
            datasetId
        } should have length 1
      }

      datasetPublicationStatusManager
        .create(
          dataset,
          PublicationStatus.Requested,
          PublicationType.Publication
        )
        .await
        .value

      testRequest(
        GET,
        s"/authorization?dataset_id=${dataset.nodeId}",
        session = nonAdminCognitoJwt,
        headers = withXOriginalURI(s"/model-schema/dataset/${dataset.nodeId}")
      ) ~>
        routes ~> check {

        val claim: Jwt.Claim = getClaim()
        claim.content.roles.collect {
          case Jwt.DatasetRole(
              Inl(datasetId),
              _,
              Some(DatasetNodeId(dataset.nodeId)),
              Some(true)
              ) =>
            datasetId
        } should have length 1
      }

      datasetPublicationStatusManager
        .create(
          dataset,
          PublicationStatus.Accepted,
          PublicationType.Publication
        )
        .await
        .value

      testRequest(
        GET,
        s"/authorization?dataset_id=${dataset.nodeId}",
        session = nonAdminCognitoJwt,
        headers = withXOriginalURI(s"/model-schema/dataset/${dataset.nodeId}")
      ) ~>
        routes ~> check {

        val claim: Jwt.Claim = getClaim()
        claim.content.roles.collect {
          case Jwt.DatasetRole(
              Inl(datasetId),
              _,
              Some(DatasetNodeId(dataset.nodeId)),
              Some(true)
              ) =>
            datasetId
        } should have length 1
      }

      datasetPublicationStatusManager
        .create(dataset, PublicationStatus.Failed, PublicationType.Publication)
        .await
        .value

      testRequest(
        GET,
        s"/authorization?dataset_id=${dataset.nodeId}",
        session = nonAdminCognitoJwt,
        headers = withXOriginalURI(s"/model-schema/dataset/${dataset.nodeId}")
      ) ~>
        routes ~> check {

        val claim: Jwt.Claim = getClaim()
        claim.content.roles.collect {
          case Jwt.DatasetRole(
              Inl(datasetId),
              _,
              Some(DatasetNodeId(dataset.nodeId)),
              Some(true)
              ) =>
            datasetId
        } should have length 1
      }

      datasetPublicationStatusManager
        .create(
          dataset,
          PublicationStatus.Completed,
          PublicationType.Publication
        )
        .await
        .value

      testRequest(
        GET,
        s"/authorization?dataset_id=${dataset.nodeId}",
        session = nonAdminCognitoJwt,
        headers = withXOriginalURI(s"/model-schema/dataset/${dataset.nodeId}")
      ) ~>
        routes ~> check {

        val claim: Jwt.Claim = getClaim()
        claim.content.roles.collect {
          case Jwt.DatasetRole(
              Inl(datasetId),
              _,
              Some(DatasetNodeId(dataset.nodeId)),
              Some(false)
              ) =>
            datasetId
        } should have length 1
      }

      datasetPublicationStatusManager
        .create(
          dataset,
          PublicationStatus.Cancelled,
          PublicationType.Publication
        )
        .await
        .value

      testRequest(
        GET,
        s"/authorization?dataset_id=${dataset.nodeId}",
        session = nonAdminCognitoJwt,
        headers = withXOriginalURI(s"/model-schema/dataset/${dataset.nodeId}")
      ) ~>
        routes ~> check {

        val claim: Jwt.Claim = getClaim()
        claim.content.roles.collect {
          case Jwt.DatasetRole(
              Inl(datasetId),
              _,
              Some(DatasetNodeId(dataset.nodeId)),
              Some(false)
              ) =>
            datasetId
        } should have length 1
      }

      datasetPublicationStatusManager
        .create(
          dataset,
          PublicationStatus.Rejected,
          PublicationType.Publication
        )
        .await
        .value

      testRequest(
        GET,
        s"/authorization?dataset_id=${dataset.nodeId}",
        session = nonAdminCognitoJwt,
        headers = withXOriginalURI(s"/model-schema/dataset/${dataset.nodeId}")
      ) ~>
        routes ~> check {

        val claim: Jwt.Claim = getClaim()
        claim.content.roles.collect {
          case Jwt.DatasetRole(
              Inl(datasetId),
              _,
              Some(DatasetNodeId(dataset.nodeId)),
              Some(false)
              ) =>
            datasetId
        } should have length 1
      }

      datasetPublicationStatusManager
        .create(dataset, PublicationStatus.Completed, PublicationType.Removal)
        .await
        .value

      testRequest(
        GET,
        s"/authorization?dataset_id=${dataset.nodeId}",
        session = nonAdminCognitoJwt,
        headers = withXOriginalURI(s"/model-schema/dataset/${dataset.nodeId}")
      ) ~>
        routes ~> check {

        val claim: Jwt.Claim = getClaim()
        claim.content.roles.collect {
          case Jwt.DatasetRole(
              Inl(datasetId),
              _,
              Some(DatasetNodeId(dataset.nodeId)),
              Some(false)
              ) =>
            datasetId
        } should have length 1
      }
    }

    "return a JWT with locked = false if the dataset is being published and the user is a publisher" in {
      val datasetsMapper = new DatasetsMapper(organizationTwo)
      val datasetManager =
        new DatasetManager(db, nonAdmin, datasetsMapper)

      val datasetPublicationStatusManager = new DatasetPublicationStatusManager(
        db,
        nonAdmin,
        new DatasetPublicationStatusMapper(organizationTwo),
        new ChangelogEventMapper(organizationTwo)
      )

      val teamManager = TeamManager(organizationManager)

      val query = for {
        dataset <- datasetManager
          .create("Test Dataset (from node ID)")

        publisherTeam <- organizationManager
          .getPublisherTeam(organizationTwo)
          .map(_._1)

        _ <- teamManager
          .addUser(publisherTeam, nonAdmin, DBPermission.Administer)

        _ <- datasetManager.addTeamCollaborator(
          dataset,
          publisherTeam,
          Role.Editor
        )

      } yield dataset

      val dataset = query.await.right.get

      datasetPublicationStatusManager
        .create(
          dataset,
          PublicationStatus.Requested,
          PublicationType.Publication
        )
        .await
        .value

      testRequest(
        GET,
        s"/authorization?dataset_id=${dataset.nodeId}",
        session = nonAdminCognitoJwt,
        headers = withXOriginalURI(s"/model-schema/dataset/${dataset.nodeId}")
      ) ~>
        routes ~> check {

        val claim: Jwt.Claim = getClaim()
        claim.content.roles.collect {
          case Jwt.DatasetRole(
              Inl(datasetId),
              _,
              Some(DatasetNodeId(dataset.nodeId)),
              Some(false)
              ) =>
            datasetId
        } should have length 1
      }

      datasetPublicationStatusManager
        .create(dataset, PublicationStatus.Failed, PublicationType.Publication)
        .await
        .value

      testRequest(
        GET,
        s"/authorization?dataset_id=${dataset.nodeId}",
        session = nonAdminCognitoJwt,
        headers = withXOriginalURI(s"/model-schema/dataset/${dataset.nodeId}")
      ) ~>
        routes ~> check {

        val claim: Jwt.Claim = getClaim()
        claim.content.roles.collect {
          case Jwt.DatasetRole(
              Inl(datasetId),
              _,
              Some(DatasetNodeId(dataset.nodeId)),
              Some(false)
              ) =>
            datasetId
        } should have length 1
      }

      // Should still be locked for publishers when Accepted

      datasetPublicationStatusManager
        .create(
          dataset,
          PublicationStatus.Accepted,
          PublicationType.Publication
        )
        .await
        .value

      testRequest(
        GET,
        s"/authorization?dataset_id=${dataset.nodeId}",
        session = nonAdminCognitoJwt,
        headers = withXOriginalURI(s"/model-schema/dataset/${dataset.nodeId}")
      ) ~>
        routes ~> check {

        val claim: Jwt.Claim = getClaim()
        claim.content.roles.collect {
          case Jwt.DatasetRole(
              Inl(datasetId),
              _,
              Some(DatasetNodeId(dataset.nodeId)),
              Some(true)
              ) =>
            datasetId
        } should have length 1
      }
    }

    "return a JWT for an authorized user with a dataset claim if the dataset is shared with the organization" in {
      val datasetsMapper = new DatasetsMapper(organizationTwo)
      val datasetManager =
        new DatasetManager(db, admin, datasetsMapper)
      val dataset = datasetManager
        .create("Test Dataset")
        .await
        .value

      datasetManager
        .setOrganizationCollaboratorRole(dataset, Some(Role.Manager))
        .await

      testRequest(
        GET,
        s"/authorization?dataset_id=${dataset.id}",
        session = nonAdminCognitoJwt,
        headers = withXOriginalURI(s"/model-schema/dataset/${dataset.id}")
      ) ~>
        routes ~> check {
        status shouldEqual OK

        val claim: Jwt.Claim = getClaim()

        claim.content shouldBe a[UserClaim]
        claim.content.roles should have length 2
      }
    }

    "return a 404 Not Found for a user requesting a dataset shared in another organization" in {
      val datasetsMapper = new DatasetsMapper(organizationOne)
      val datasetManager =
        new DatasetManager(db, admin, datasetsMapper)
      val dataset = datasetManager
        .create("Test Dataset")
        .await
        .value

      datasetManager
        .setOrganizationCollaboratorRole(dataset, Some(Role.Manager))
        .await

      // nonAdmin belongs to organizationTwo
      testRequest(
        GET,
        s"/authorization?dataset_id=${dataset.id}",
        session = nonAdminCognitoJwt,
        headers = withXOriginalURI(s"/model-schema/dataset/${dataset.id}")
      ) ~>
        routes ~> check {
        status shouldEqual Unauthorized
      }
    }

    "return a JWT containing an organization's encryption key id for an authorized user with write permissions" in {
      testRequest(
        GET,
        "/authorization",
        session = nonAdminCognitoJwt,
        headers = withXOriginalURI(s"/model-schema")
      ) ~> routes ~> check {
        status shouldEqual OK

        val claim: Jwt.Claim = getClaim()

        val maybeEncryptionId = claim.content.roles.head
          .asInstanceOf[Jwt.OrganizationRole]
          .encryption_key_id

        claim.content shouldBe a[UserClaim]
        maybeEncryptionId shouldBe Some(EncryptionKeyId("NO_ENCRYPTION_KEY"))
      }
    }

    "return a JWT containing an organization's enabled features for an authorized user" in {
      val features: List[Feature] =
        List(Feature.ConceptsFeature)

      features.map { feature =>
        testDIContainer.organizationManager
          .setFeatureFlag(FeatureFlag(organizationTwo.id, feature))
          .await
          .value
      }

      testRequest(
        GET,
        "/authorization",
        session = nonAdminCognitoJwt,
        headers = withXOriginalURI(s"/model-schema")
      ) ~> routes ~> check {
        status shouldEqual OK

        val claim: Jwt.Claim = getClaim()

        val enabledFeatures = claim.content.roles.head
          .asInstanceOf[Jwt.OrganizationRole]
          .enabled_features
          .get

        enabledFeatures shouldBe features
      }
    }

    "return a 404 Not Found response when an invalid dataset_id is provided" in {
      testRequest(
        GET,
        s"/authorization?dataset_id=1",
        session = nonAdminCognitoJwt,
        headers = withXOriginalURI(s"/model-schema/dataset/1")
      ) ~>
        routes ~> check {
        status shouldEqual Unauthorized

        header("Authorization") shouldBe None
      }
    }

    "return a 401 Unauthorized response when an invalid JWT is provided" in {
      testRequest(
        GET,
        s"/authorization?dataset_id=1",
        session = UUID.randomUUID.toString.some,
        headers = withXOriginalURI(s"/analytics/dataset/1")
      ) ~>
        routes ~> check {
        status shouldEqual Unauthorized

        header("Authorization") shouldBe None
      }
    }

    "return a 401 Unauthorized response when an expired JWT is provided" in {

      val cognitoId = createCognitoUser(testDIContainer, nonAdmin)

      val expiredJwt = cognitoJwkProvider.generateCognitoToken(
        cognitoId,
        cognitoConfig.userPool,
        issuedAt = Instant.now.minusSeconds(60 * 70),
        validUntil = Instant.now.minusSeconds(60 * 10)
      )

      testRequest(
        GET,
        "/authorization",
        session = Some(expiredJwt),
        headers = withXOriginalURI("/model-schema")
      ) ~>
        routes ~> check {
        status shouldEqual Unauthorized

        header("Authorization") shouldBe None
      }
    }

    // TODO more Cognito JWT tests

    "return 200 when valid organization_id is provided" in {
      testRequest(
        GET,
        s"/authorization?organization_id=${organizationTwo.id}",
        session = nonAdminCognitoJwt,
        headers = withXOriginalURI("/model-schema")
      ) ~>
        routes ~> check {
        status shouldEqual OK
      }
    }

    "return 200 when valid organization_id node id is provided" in {
      testRequest(
        GET,
        s"/authorization?organization_id=${organizationTwo.nodeId}",
        session = nonAdminCognitoJwt,
        headers = withXOriginalURI("/model-schema")
      ) ~>
        routes ~> check {
        status shouldEqual OK
      }
    }

    "return 401 Unauthorized when an invalid organization_id is provided" in {
      testRequest(
        GET,
        "/authorization?organization_id=5555",
        session = nonAdminCognitoJwt,
        headers = withXOriginalURI("/model-schema")
      ) ~>
        routes ~> check {
        status shouldEqual Unauthorized
      }
    }

    "ignore content length header for entities leaving it to upstream services to verify max content size" in {
      import akka.http.scaladsl.model.HttpMethods._
      testRequest(
        GET,
        "/authorization",
        session = nonAdminCognitoJwt,
        headers = withXOriginalURI(s"/model-schema") ++ ImmutableSeq(
          `Content-Length`(670000000)
        )
      ) ~> routes ~> check {
        status shouldBe OK
      }
    }
  }

  "PUT /session/switch-organization route" should {

    def preferredOrganization(user: User): Organization =
      (for {
        refreshedUser <- userManager.get(user.id)
        organization <- userManager.getPreferredOrganization(refreshedUser)
      } yield organization).awaitFinite().right.get

    "switch the organization in a user's session" in {
      // Confirm the session starts belonging to Organization Two
      preferredOrganization(nonAdmin) shouldBe organizationTwo

      testRequest(
        PUT,
        s"/session/switch-organization?organization_id=${organizationOne.id}",
        session = nonAdminCognitoJwt
      ) ~>
        routes ~> check {
        status shouldEqual OK

        // Verify the session now belonging to Organization One
        preferredOrganization(nonAdmin) shouldBe organizationOne

        responseAs[UserDTO].preferredOrganization.get shouldBe organizationOne.nodeId
      }
    }

    "switch the organization in a user's session with a node ID" in {
      // Confirm the session starts belonging to Organization Two
      preferredOrganization(nonAdmin) shouldBe organizationTwo

      testRequest(
        PUT,
        s"/session/switch-organization?organization_id=${organizationOne.nodeId}",
        session = nonAdminCognitoJwt
      ) ~>
        routes ~> check {
        status shouldEqual OK

        // Verify the session now belonging to Organization One
        preferredOrganization(nonAdmin) shouldBe organizationOne

        responseAs[UserDTO].preferredOrganization.get shouldBe organizationOne.nodeId
      }
    }

    "switch the organization in an admin user's session to which it does not belong" in {
      // Confirm the session starts belonging to Organization One
      preferredOrganization(admin) shouldBe organizationOne

      testRequest(
        PUT,
        s"/session/switch-organization?organization_id=${organizationTwo.id}",
        session = adminCognitoJwt
      ) ~>
        routes ~> check {
        status shouldEqual OK

        // Verify the session now belonging to Organization Two
        preferredOrganization(admin) shouldBe organizationTwo

        responseAs[UserDTO].preferredOrganization.get shouldBe organizationTwo.nodeId
      }
    }

    "reject a request to switch to an organization a user does not have access to" in {

      // Confirm the session starts belonging to Organization One
      preferredOrganization(owner) shouldBe organizationOne

      testRequest(
        PUT,
        s"/session/switch-organization?organization_id=${organizationTwo.id}",
        session = ownerCognitoJwt
      ) ~>
        routes ~> check {
        status shouldEqual Unauthorized

        // Verify the session does not belong to Organization Two

        preferredOrganization(owner) should not be organizationTwo
      }
    }

    "reject a request using an API session" in {
      // Create an API session that belongs to Organization Two
      val token = testDIContainer.tokenManager
        .create(
          "switch-organization-test",
          nonAdmin,
          organizationTwo,
          mockCognito
        )
        .await
        .value
        ._1

      val apiJwt = createCognitoJwtFromToken(token)

      testRequest(
        PUT,
        s"/session/switch-organization?organization_id=${organizationOne.id}",
        session = Some(apiJwt)
      ) ~>
        routes ~> check {
        status shouldEqual Forbidden
      }
    }
  }

  private def getClaim(): Jwt.Claim = {
    val authorizationHeader: Option[HttpHeader] = header("Authorization")

    authorizationHeader shouldBe defined

    val bearer: String = authorizationHeader.get.value
      .stripPrefix("Bearer")
      .trim

    Jwt.parseClaim(Jwt.Token(bearer))(testDIContainer).toOption.get
  }
}
