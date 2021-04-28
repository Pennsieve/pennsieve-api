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

import com.pennsieve.aws.cognito.{
  CognitoConfig,
  CognitoPoolConfig,
  MockJwkProvider
}
import com.pennsieve.test.helpers.EitherValue._
import com.pennsieve.core.utilities._
import com.pennsieve.db._
import com.pennsieve.managers._
import com.pennsieve.models._
import com.pennsieve.traits.PostgresProfile.api._
import software.amazon.awssdk.regions.Region

import scala.concurrent.ExecutionContext.Implicits.global

trait CognitoJwtSeed[
  SeedContainer <: OrganizationManagerContainer with UserManagerContainer
] extends CoreSeed[SeedContainer] {

  var adminCognitoJwt: Option[String] = None
  var nonAdminCognitoJwt: Option[String] = None
  var ownerCognitoJwt: Option[String] = None

  implicit lazy val cognitoJwkProvider = new MockJwkProvider()

  implicit val cognitoConfig: CognitoConfig = CognitoConfig(
    Region.US_EAST_1,
    CognitoPoolConfig(
      Region.US_EAST_1,
      "user-pool-id",
      "client-id",
      _ => cognitoJwkProvider
    ),
    CognitoPoolConfig(
      Region.US_EAST_1,
      "token-pool-id",
      "client-id",
      _ => cognitoJwkProvider
    )
  )

  def createCognitoJwt(container: SeedContainer, user: User): String = {
    user.cognitoId match {
      case Some(cognitoId) =>
        cognitoJwkProvider.generateValidCognitoToken(
          cognitoId,
          cognitoConfig.userPool
        )
      case None => throw new Exception("user does not have Cognito ID")
    }
  }

  def createCognitoJwtFromToken(token: Token): String = {
    cognitoJwkProvider.generateValidCognitoToken(
      token.cognitoId,
      cognitoConfig.tokenPool
    )
  }

  override def seed(container: SeedContainer): Unit = {
    super.seed(container)

    adminCognitoJwt = Some(createCognitoJwt(container, admin))
    nonAdminCognitoJwt = Some(createCognitoJwt(container, nonAdmin))
    ownerCognitoJwt = Some(createCognitoJwt(container, owner))
  }
}
