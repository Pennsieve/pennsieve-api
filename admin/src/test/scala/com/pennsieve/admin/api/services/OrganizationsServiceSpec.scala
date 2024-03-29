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

package com.pennsieve.admin.api.services

import akka.http.scaladsl.model.ContentTypes
import akka.util.ByteString
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import com.pennsieve.admin.api.{ AdminContainer, Router }
import com.pennsieve.admin.api.dtos.UserDTO
import com.pennsieve.admin.api.Router.{
  AdminETLServiceContainer,
  InsecureResourceContainer,
  SecureResourceContainer
}

import java.time.{ ZoneOffset, ZonedDateTime }
import com.pennsieve.aws.s3.LocalS3Container
import com.pennsieve.aws.email.LocalEmailContainer
import com.pennsieve.core.utilities._
import com.pennsieve.messages._
import com.pennsieve.models.DBPermission.Owner
import com.pennsieve.models.SubscriptionStatus.{
  ConfirmedSubscription,
  PendingSubscription
}
import com.pennsieve.models._
import com.pennsieve.models.DateVersion._
import com.pennsieve.aws.queue.LocalSQSContainer
import akka.http.scaladsl.model.HttpMethods.{ DELETE, GET, POST, PUT }
import akka.http.scaladsl.model.StatusCodes.{
  BadRequest,
  Forbidden,
  NotFound,
  OK
}
import akka.http.scaladsl.model.headers.{ Authorization, OAuth2BearerToken }
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.testkit.TestKitBase
import com.pennsieve.auth.middleware.Jwt.Role.RoleIdentifier
import com.pennsieve.auth.middleware.{ Jwt, OrganizationId }
import com.pennsieve.clients._
import io.circe.syntax._
import io.circe.parser._
import shapeless.syntax.inject._
import org.scalatest.OptionValues._
import org.scalatest.EitherValues._

import scala.jdk.CollectionConverters._
import scala.compat.java8.FutureConverters._
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

class OrganizationsServiceSpec extends AdminServiceSpec {

  var queueUrl: String = _

  override def afterStart() = {
    super.afterStart()
    queueUrl = testDIContainer.sqs.createQueue("test").await.queueUrl
  }

  override def beforeEach() = {
    super.beforeEach()
    testDIContainer.sqs.purgeQueue(queueUrl).await
  }

  "organizations service" should {

    "return all organizations to an admin user" in {
      testRequest(GET, "/organizations", session = adminCognitoJwt) ~>
        routes ~> check {
        import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

        status shouldEqual OK
        responseAs[List[Organization]] shouldBe List(
          organizationOne,
          organizationTwo
        )
      }
    }

    "return all inactive organizations to an admin user" in {
      testRequest(GET, "/organizations/inactive", session = adminCognitoJwt) ~>
        routes ~> check {
        import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

        status shouldEqual OK
        responseAs[List[Organization]] shouldBe List()
      }
    }

    "return all organizations with an admin JWT" in {
      val organizationRole: Jwt.Role = Jwt.OrganizationRole(
        OrganizationId(organizationOne.id)
          .inject[RoleIdentifier[OrganizationId]],
        Role.Owner
      )

      val token =
        JwtAuthenticator.generateUserToken(
          1.minute,
          admin,
          List(organizationRole)
        )(jwtConfig)

      testRequest(
        GET,
        "/organizations",
        headers = List(Authorization(OAuth2BearerToken(token.value)))
      ) ~>
        routes ~> check {
        import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

        status shouldEqual OK
        responseAs[List[Organization]] should contain theSameElementsAs (List(
          organizationTwo,
          organizationOne
        ))
      }
    }

    "not return any organization to a non admin user" in {
      testRequest(GET, "/organizations", session = nonAdminCognitoJwt) ~>
        routes ~> check {
        status shouldEqual Forbidden
        responseAs[String] should be(
          "The supplied authentication is not authorized to access this resource"
        )
      }
    }

    "not return any organization to a non admin JWT" in {
      val organizationRole: Jwt.Role = Jwt.OrganizationRole(
        OrganizationId(organizationOne.id)
          .inject[RoleIdentifier[OrganizationId]],
        Role.Owner
      )

      val token =
        JwtAuthenticator.generateUserToken(
          1.minute,
          nonAdmin,
          List(organizationRole)
        )(jwtConfig)

      testRequest(
        GET,
        "/organizations",
        headers = List(Authorization(OAuth2BearerToken(token.value)))
      ) ~>
        routes ~> check {
        status shouldEqual Forbidden
        responseAs[String] should be(
          "The supplied authentication is not authorized to access this resource"
        )
      }
    }

    "return the requested organization to an admin user" in {
      testRequest(
        GET,
        s"/organizations/${organizationOne.nodeId}",
        session = adminCognitoJwt
      ) ~>
        routes ~> check {
        import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

        status shouldEqual OK
        responseAs[Organization] should be(organizationOne)
      }
    }

    "return the requested organization owners to an admin user" in {
      testRequest(
        GET,
        s"/organizations/${organizationOne.nodeId}/owners",
        session = adminCognitoJwt
      ) ~>
        routes ~> check {
        import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

        status shouldEqual OK
        responseAs[List[UserDTO]] should contain theSameElementsAs List(
          UserDTO(owner)
        )
      }
    }

    "set a new owner user" in {
      val body = UpdateOrganizationUserPermission(
        userId = nonAdmin.nodeId,
        permission = Owner
      )
      testRequest(
        PUT,
        s"/organizations/${organizationOne.nodeId}/users?userId=${nonAdmin.nodeId}",
        Some(body.asJson),
        session = adminCognitoJwt
      ) ~>
        routes ~> check {
        status shouldEqual OK

        testRequest(
          GET,
          s"/organizations/${organizationOne.nodeId}/owners",
          session = adminCognitoJwt
        ) ~>
          routes ~> check {
          import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

          status shouldEqual OK
          responseAs[List[UserDTO]] should contain(UserDTO(owner))
        }
      }
    }

    "not return the requested organization to a non-admin user" in {
      testRequest(
        GET,
        s"/organizations/${organizationOne.nodeId}",
        session = nonAdminCognitoJwt
      ) ~>
        routes ~> check {
        status shouldEqual Forbidden
        responseAs[String] should be(
          "The supplied authentication is not authorized to access this resource"
        )
      }
    }

    "not return anything with an invalid id" in {
      testRequest(GET, s"/organizations/${admin.id}", session = adminCognitoJwt) ~>
        routes ~> check {
        status shouldEqual BadRequest
        responseAs[String] should be("malformed organization id")
      }
    }

    "not return an organization that doesn't exist" in {
      testRequest(
        GET,
        "/organizations/N:organization:test",
        session = adminCognitoJwt
      ) ~>
        routes ~> check {
        status shouldEqual NotFound
        responseAs[String] should be(
          "failed to retrieve organization with error: N:organization:test not found"
        )
      }
    }

    "allow an admin user to create a new organization" in {
      createAndMigrateSchema(3)

      val body = Some(NewOrganization("new-org-name", "new-org-slug").asJson)

      testRequest(POST, s"/organizations", body, session = adminCognitoJwt) ~>
        routes ~> check {
        import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

        status shouldEqual OK
        val newOrganization = responseAs[Organization]

        testDIContainer.jobSchedulingServiceClient
          .asInstanceOf[LocalJobSchedulingServiceClient]
          .organizationQuotas
          .headOption
          .value should be(newOrganization.id -> Quota(10))

        testRequest(
          GET,
          s"/organizations/${newOrganization.nodeId}",
          session = adminCognitoJwt
        ) ~>
          routes ~> check {
          status shouldEqual OK
          responseAs[Organization] should be(newOrganization)
        }
      }
    }

    "allow an admin user to create a new organization and specify a Trial subscription" in {
      createAndMigrateSchema(3)

      val body = Some(
        NewOrganization("new-org-name", "new-org-slug", Some("Trial")).asJson
      )

      testRequest(POST, s"/organizations", body, session = adminCognitoJwt) ~>
        routes ~> check {
        import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

        status shouldEqual OK
        val newOrganization = responseAs[Organization]

        testRequest(
          GET,
          s"/organizations/${newOrganization.nodeId}/subscription",
          session = adminCognitoJwt
        ) ~>
          routes ~> check {
          status shouldEqual OK

          import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

          responseAs[Subscription] should have(
            Symbol("status")(PendingSubscription)
          )
          responseAs[Subscription] should have(Symbol("type")(Some("Trial")))
        }
      }
    }

    "allow an admin user to update an existing organization" in {

      val updated = organizationTwo.copy(name = "updatedOrg")
      val body = Some(updated.asJson)

      testRequest(PUT, s"/organizations", body, session = adminCognitoJwt) ~>
        routes ~> check {
        import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

        status shouldEqual OK
        val updatedOrganization = responseAs[Organization]

        testRequest(
          GET,
          s"/organizations/${updatedOrganization.nodeId}",
          session = adminCognitoJwt
        ) ~>
          routes ~> check {
          status shouldEqual OK

          val response = responseAs[Organization]
          response.id should be(updated.id)
          response.name should be(updated.name)
          response.createdAt should be(updated.createdAt)
          response.updatedAt should not be (updated.updatedAt)
        }
      }
    }

    "allow an admin user to set a feature flag for an organization" in {

      val updated = UpdateFeatureFlag(Feature.ClinicalManagementFeature, true)
      val body = Some(updated.asJson)

      testRequest(
        PUT,
        s"/organizations/${organizationOne.nodeId}/feature",
        body,
        session = adminCognitoJwt
      ) ~>
        routes ~> check {
        import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

        status shouldEqual OK
        val result = responseAs[FeatureFlag]
        result.organizationId should be(organizationOne.id)
        result.feature should be(Feature.ClinicalManagementFeature)
        result.enabled should be(true)
      }
    }

    "not allow an non-admin user to create a new organization" in {

      val body = Some(NewOrganization("new-org-name", "new-org-slug").asJson)

      testRequest(POST, s"/organizations", body, session = nonAdminCognitoJwt) ~>
        routes ~> check {
        status shouldEqual Forbidden
        responseAs[String] should be(
          "The supplied authentication is not authorized to access this resource"
        )
      }
    }

    "reject a request to create a new organization with an existing slug" in {

      val body =
        Some(NewOrganization("new-org-name", "organization_one").asJson)

      testRequest(POST, s"/organizations", body, session = adminCognitoJwt) ~>
        routes ~> check {
        status shouldEqual BadRequest
        responseAs[String] should be("requirement failed: slug must be unique")
      }

      testRequest(GET, "/organizations", session = adminCognitoJwt) ~>
        routes ~> check {
        import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

        responseAs[List[Organization]] should contain theSameElementsAs (List(
          organizationTwo,
          organizationOne
        ))
      }
    }

    "reject a request to create a new organization when the matching schema doesn't exist" in {
      val body =
        Some(NewOrganization("new-schemaless-org", "schemaless_org").asJson)

      testRequest(POST, s"/organizations", body, session = adminCognitoJwt) ~>
        routes ~> check {
        status shouldEqual BadRequest
        responseAs[String] should be("requirement failed: schema not found")
      }
    }

    "return the requested organization users to an admin user" in {
      val user = UserWithPermission(UserDTO(admin), DBPermission.Administer)
      val nonAdminUser =
        UserWithPermission(UserDTO(nonAdmin), DBPermission.Delete)
      val ownerUser = UserWithPermission(UserDTO(owner), DBPermission.Owner)

      testRequest(
        GET,
        s"/organizations/${organizationOne.nodeId}/users",
        session = adminCognitoJwt
      ) ~>
        routes ~> check {
        import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

        status shouldEqual OK
        responseAs[List[UserWithPermission]] should contain theSameElementsAs List(
          user,
          nonAdminUser,
          ownerUser
        )
      }
    }

    "return the requested organization users to an admin user for an organization they don't belong to" in {
      val user = UserWithPermission(UserDTO(nonAdmin), DBPermission.Delete)
      val adminWithPerm =
        UserWithPermission(UserDTO(admin), DBPermission.Administer)

      testRequest(
        GET,
        s"/organizations/${organizationTwo.nodeId}/users",
        session = adminCognitoJwt
      ) ~>
        routes ~> check {
        import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

        status shouldEqual OK
        responseAs[List[UserWithPermission]] should contain theSameElementsAs List(
          adminWithPerm,
          user
        )
      }
    }

    "not return the requested organization users to a non-admin user" in {
      testRequest(
        GET,
        s"/organizations/${organizationOne.nodeId}/users",
        session = nonAdminCognitoJwt
      ) ~>
        routes ~> check {
        status shouldEqual Forbidden
        responseAs[String] should be(
          "The supplied authentication is not authorized to access this resource"
        )
      }
    }

    "not return the requested organization users when request is invalid" in {
      testRequest(
        GET,
        s"/organizations/invalidOrgId/users",
        session = adminCognitoJwt
      ) ~>
        routes ~> check {
        status shouldEqual NotFound
        responseAs[String] should be(
          "failed to retrieve users in organization with error: invalidOrgId not found"
        )
      }
    }

    "show the current subscription" in {

      testRequest(
        GET,
        s"/organizations/${organizationOne.nodeId}/subscription",
        session = adminCognitoJwt
      ) ~>
        routes ~> check {
        status shouldEqual OK

        import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

        responseAs[Subscription] should have(
          Symbol("status")(ConfirmedSubscription)
        )
        responseAs[Subscription] should have(
          Symbol("acceptedForOrganization")(Some("Organization One"))
        )
        responseAs[Subscription] should have(
          Symbol("acceptedBy")(Some("owner"))
        )
      }
    }

    "reset the accepted subscription" in {
      testRequest(
        DELETE,
        s"/organizations/${organizationOne.nodeId}/subscription",
        session = adminCognitoJwt
      ) ~>
        routes ~> check {
        status shouldEqual OK

        testRequest(
          GET,
          s"/organizations/${organizationOne.nodeId}/subscription",
          session = adminCognitoJwt
        ) ~>
          routes ~> check {
          status shouldEqual OK
          import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

          responseAs[Subscription] should have(
            Symbol("status")(PendingSubscription)
          )
          responseAs[Subscription] should have(
            Symbol("acceptedForOrganization")(None)
          )
          responseAs[Subscription] should have(Symbol("acceptedBy")(None))
        }

      }
    }

    "update the subscription type" in {

      testRequest(
        GET,
        s"/organizations/${organizationOne.nodeId}/subscription",
        session = adminCognitoJwt
      ) ~>
        routes ~> check {
        status shouldEqual OK
        import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

        responseAs[Subscription] should have(
          Symbol("status")(ConfirmedSubscription)
        )
        responseAs[Subscription] should have(Symbol("type")(None))
        responseAs[Subscription] should have(
          Symbol("acceptedForOrganization")(Some("Organization One"))
        )
        responseAs[Subscription] should have(
          Symbol("acceptedBy")(Some("owner"))
        )
      }

      val payload = SetSubscriptionType(isTrial = true).asJson

      testRequest(
        PUT,
        s"/organizations/${organizationOne.nodeId}/subscription",
        Some(payload),
        session = adminCognitoJwt
      ) ~>
        routes ~> check {
        status shouldEqual OK

        import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

        responseAs[Subscription] should have(
          Symbol("status")(ConfirmedSubscription)
        )
        responseAs[Subscription] should have(Symbol("type")(Some("Trial")))
        responseAs[Subscription] should have(
          Symbol("acceptedForOrganization")(Some("Organization One"))
        )
        responseAs[Subscription] should have(
          Symbol("acceptedBy")(Some("owner"))
        )
      }
    }

    "start the storage cache population job" in {
      testRequest(
        GET,
        s"/organizations/${organizationOne.nodeId}/storage",
        session = adminCognitoJwt
      ) ~>
        routes ~> check {
        status shouldEqual OK
      }

      val sentMessage = testDIContainer.sqs.client
        .receiveMessage(
          ReceiveMessageRequest.builder().queueUrl(queueUrl).build()
        )
        .toScala
        .await
        .messages
        .asScala
        .head
        .body

      parse(sentMessage).value.hcursor
        .downField("CachePopulationJob")
        .get[Int]("organizationId")
        .value shouldBe organizationOne.id
    }

    "start the storage cache population job in a different organization" in {
      testRequest(
        GET,
        s"/organizations/${organizationTwo.nodeId}/storage",
        session = adminCognitoJwt
      ) ~>
        routes ~> check {
        status shouldEqual OK
      }

      val sentMessage = testDIContainer.sqs.client
        .receiveMessage(
          ReceiveMessageRequest.builder().queueUrl(queueUrl).build()
        )
        .toScala
        .await
        .messages
        .asScala
        .head
        .body

      parse(sentMessage).value.hcursor
        .downField("CachePopulationJob")
        .get[Int]("organizationId")
        .value shouldBe organizationTwo.id
    }

    "allow an admin user to upload new custom terms" in {
      val body = "<html><body>Custom terms</body></html>"

      testRequestWithBytes(
        PUT,
        s"/organizations/${organizationOne.nodeId}/custom-terms-of-service?isNewVersion=true",
        Some(ByteString(body)),
        session = adminCognitoJwt,
        contentType = ContentTypes.`application/octet-stream`
      ) ~>
        routes ~> check {
        status shouldEqual OK
        val version =
          DateVersion.from(responseAs[String].replace("\"", "")).value

        val customTOSClient =
          testDIContainer.customTermsOfServiceClient
            .asInstanceOf[MockCustomTermsOfServiceClient]

        // confirm that the HTML was uploaded to the mock s3 bucket
        customTOSClient.bucket(organizationOne.nodeId)(version) shouldBe body

        // confirm that the database was updated with the new version
        val versionDB = organizationManager
          .getCustomTermsOfServiceVersion(organizationOne.nodeId)
          .await
        versionDB.value.toDateVersion shouldBe version
      }
    }

    "custom terms version is not updated by default" in {
      val body = "<html><body>Custom terms</body></html>"

      val existingVersion = ZonedDateTime.now(ZoneOffset.UTC).minusDays(10)

      organizationManager
        .updateCustomTermsOfServiceVersion(
          organizationOne.nodeId,
          existingVersion
        )
        .await

      testRequestWithBytes(
        PUT,
        s"/organizations/${organizationOne.nodeId}/custom-terms-of-service",
        Some(ByteString(body)),
        session = adminCognitoJwt,
        contentType = ContentTypes.`application/octet-stream`
      ) ~>
        routes ~> check {
        status shouldEqual OK
        val version =
          DateVersion.from(responseAs[String].replace("\"", "")).value

        // returned version shuold be the same as existing version
        version shouldBe existingVersion.toDateVersion

        val customTOSClient =
          testDIContainer.customTermsOfServiceClient
            .asInstanceOf[MockCustomTermsOfServiceClient]

        // confirm that the HTML was uploaded to the mock s3 bucket
        customTOSClient.bucket(organizationOne.nodeId)(version) shouldBe body

        // confirm that the database was not updated with the new version
        val versionDB = organizationManager
          .getCustomTermsOfServiceVersion(organizationOne.nodeId)
          .await
        versionDB.value.toDateVersion shouldBe existingVersion.toDateVersion

      }
    }

    "prevent a non-admin user from uploading new custom terms" in {
      val body = ByteString("<html><body>Custom terms</body></html>")

      testRequestWithBytes(
        PUT,
        s"/organizations/${organizationOne.nodeId}/custom-terms-of-service",
        Some(body),
        session = nonAdminCognitoJwt,
        contentType = ContentTypes.`application/octet-stream`
      ) ~>
        routes ~> check {
        status shouldEqual Forbidden
        responseAs[String] should be(
          "The supplied authentication is not authorized to access this resource"
        )
      }
    }
  }
}
