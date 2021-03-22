// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.authorization.routes

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
import com.blackfynn.akka.http.EitherValue._
import com.blackfynn.auth.middleware.{
  DatasetId,
  DatasetNodeId,
  EncryptionKeyId,
  Jwt,
  OrganizationId,
  Permission,
  UserClaim
}
import com.blackfynn.auth.middleware.Jwt.DatasetRole
import com.blackfynn.db.{
  ChangelogEventMapper,
  ContributorMapper,
  DatasetPublicationStatusMapper,
  DatasetsMapper
}
import com.blackfynn.domain.Sessions.APISession
import com.blackfynn.dtos.UserDTO
import com.blackfynn.managers.{
  ContributorManager,
  DatasetManager,
  DatasetPreviewManager,
  DatasetPublicationStatusManager,
  TeamManager,
  UserManager
}
import com.blackfynn.models.{
  DBPermission,
  DatasetPreviewer,
  EmbargoAccess,
  Feature,
  FeatureFlag,
  OrganizationNodeId,
  PackageState,
  PublicationStatus,
  PublicationType,
  Role
}
import com.blackfynn.traits.PostgresProfile.api._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import java.util.UUID

import com.blackfynn.auth.middleware.Jwt.Role.RoleIdentifier
import shapeless._

import scala.collection.immutable.{ Seq => ImmutableSeq }
import scala.concurrent.duration._
import scala.concurrent._

class AuthorizationRoutesSpec
    extends AuthorizationServiceSpec
    with TestKitBase {

  def withXOriginalURI(uri: String): ImmutableSeq[HttpHeader] =
    ImmutableSeq(RawHeader("X-Original-URI", uri))

  "GET /authorization route" should {

    "return a JWT for an authorized user" in {
      testRequest(
        GET,
        "/authorization",
        session = nonAdminSession,
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
        session = nonAdminSession,
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
        session = nonAdminSession,
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
        session = nonAdminSession,
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
        session = nonAdminSession,
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
        session = nonAdminSession,
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
        session = nonAdminSession,
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
        session = nonAdminSession,
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
        session = nonAdminSession,
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
        session = nonAdminSession,
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
        session = nonAdminSession,
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
        session = nonAdminSession,
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
        session = nonAdminSession,
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
        session = nonAdminSession,
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
        session = nonAdminSession,
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
        session = nonAdminSession,
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
        session = nonAdminSession,
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
        session = nonAdminSession,
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

    "return a JWT for an authorized user with a workspace claim" in {
      testRequest(
        GET,
        s"/authorization?workspace_id=1",
        session = nonAdminSession,
        headers = withXOriginalURI(s"/analytics/workspaces/1")
      ) ~>
        routes ~> check {
        status shouldEqual OK

        val claim: Jwt.Claim = getClaim()

        claim.content shouldBe a[UserClaim]
        claim.content.roles should have length 2
      }
    }

    "return a 404 Not Found response when an invalid dataset_id is provided" in {
      testRequest(
        GET,
        s"/authorization?dataset_id=1",
        session = nonAdminSession,
        headers = withXOriginalURI(s"/model-schema/dataset/1")
      ) ~>
        routes ~> check {
        status shouldEqual Unauthorized

        header("Authorization") shouldBe None
      }
    }

    "return a 404 Not Found response when an invalid workspace_id is provided" in {
      testRequest(
        GET,
        s"/authorization?workspace_id=999",
        session = adminSession,
        headers = withXOriginalURI(s"/analytics/workspace/999")
      ) ~>
        routes ~> check {
        status shouldEqual Unauthorized

        header("Authorization") shouldBe None
      }
    }

    "return a 401 Unauthorized response when an invalid session is provided" in {
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

    "return 200 when valid organization_id is provided" in {
      testRequest(
        GET,
        s"/authorization?organization_id=${organizationTwo.id}",
        session = nonAdminSession,
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
        session = nonAdminSession,
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
        session = nonAdminSession,
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
        session = nonAdminSession,
        headers = withXOriginalURI(s"/model-schema") ++ ImmutableSeq(
          `Content-Length`(670000000)
        )
      ) ~> routes ~> check {
        status shouldBe OK
      }
    }

    "return not authorized for a blind reviewer role attempting to access anything outside trials service" in {
      val request = testRequest(
        GET,
        "/authorization",
        session = blindReviewerSession,
        headers = withXOriginalURI(s"/model-schema")
      )

      request ~> routes ~> check(status shouldBe Forbidden)
    }

    "return ok for a blind reviewer attempting to access trials service" in {
      val request = testRequest(
        GET,
        "/authorization",
        session = blindReviewerSession,
        headers = withXOriginalURI(s"/trials")
      )

      request ~> routes ~> check(status shouldBe OK)
    }

    "return the trial dataset for a trials request" in {
      val datasetsMapper = new DatasetsMapper(organizationOne)
      val datasetManager =
        new DatasetManager(db, blindReviewer, datasetsMapper)
      val dataset = datasetManager.create("Test Dataset").await.value

      val request = testRequest(
        GET,
        s"/authorization?dataset_id=${dataset.id}",
        session = blindReviewerSession,
        headers = withXOriginalURI(s"/trials/${dataset.id}")
      )

      request ~> routes ~> check {
        status shouldBe OK

        val claim = getClaim()

        claim.content shouldBe a[UserClaim]
        claim.content.roles should have length 2
      }
    }
  }

  "PUT /session/switch-organization route" should {

    "switch the organization in a user's session" in {
      // Confirm the session starts belonging to Organization Two
      sessionManager
        .get(nonAdminSession.get)
        .right
        .get
        .organizationId shouldBe organizationTwo.nodeId

      testRequest(
        PUT,
        s"/session/switch-organization?organization_id=${organizationOne.id}",
        session = nonAdminSession
      ) ~>
        routes ~> check {
        status shouldEqual OK

        // Verify the session now belonging to Organization One
        sessionManager
          .get(nonAdminSession.get)
          .right
          .get
          .organizationId shouldBe organizationOne.nodeId

        responseAs[UserDTO].preferredOrganization.get shouldBe organizationOne.nodeId
      }
    }

    "switch the organization in an admin user's session to which it does not belong" in {
      // Confirm the session starts belonging to Organization One
      sessionManager
        .get(adminSession.get)
        .right
        .get
        .organizationId shouldBe organizationOne.nodeId

      testRequest(
        PUT,
        s"/session/switch-organization?organization_id=${organizationTwo.id}",
        session = adminSession
      ) ~>
        routes ~> check {
        status shouldEqual OK

        // Verify the session now belonging to Organization Two
        sessionManager
          .get(adminSession.get)
          .right
          .get
          .organizationId shouldBe organizationTwo.nodeId

        responseAs[UserDTO].preferredOrganization.get shouldBe organizationTwo.nodeId
      }
    }

    "reject a request to switch to an organization a user does not have access to" in {
      // Confirm the session starts belonging to Organization One
      sessionManager
        .get(blindReviewerSession.get)
        .right
        .get
        .organizationId shouldBe organizationOne.nodeId

      testRequest(
        PUT,
        s"/session/switch-organization?organization_id=${organizationTwo.id}",
        session = blindReviewerSession
      ) ~>
        routes ~> check {
        status shouldEqual Unauthorized

        // Verify the session does not belong to Organization Two
        sessionManager
          .get(blindReviewerSession.get)
          .right
          .get
          .organizationId should not be organizationTwo.nodeId
      }
    }

    "reject a request using an API session" in {
      // Create an API session that belongs to Organization Two
      val token = testDIContainer.tokenManager
        .create("switch-organization-test", nonAdmin, organizationTwo)
        .await
        .value
        ._1

      val apiSession = sessionManager
        .generateAPISession(token, 6000, testDIContainer.tokenManager)
        .await
        .value

      testRequest(
        PUT,
        s"/session/switch-organization?organization_id=${organizationOne.id}",
        session = Some(apiSession.uuid)
      ) ~>
        routes ~> check {
        status shouldEqual Forbidden

        // Verify the session does not belong to Organization One
        sessionManager
          .get(apiSession.uuid)
          .right
          .get
          .organizationId should not be organizationOne.nodeId
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
