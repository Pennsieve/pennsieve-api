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

package com.pennsieve.aws.cognito

import com.auth0.jwk.JwkProvider
import net.ceedubs.ficus.Ficus._
import com.typesafe.config.Config
import software.amazon.awssdk.regions.Region
import java.net.URL

case class CognitoConfig(
  region: Region,
  userPool: CognitoPoolConfig, // Pennsieve users
  tokenPool: CognitoPoolConfig // Client token pool
) {

  // TODO: separate providers for each pool
  lazy val jwkProvider = CognitoJWTAuthenticator.getJwkProvider(userPool)

}

/**
  * Config for a single Cognito User Pool
  */
case class CognitoPoolConfig(region: Region, id: String, appClientId: String) {

  def endpoint: String =
    s"https://cognito-idp.${region.toString}.amazonaws.com/$id"

  /**
    * Must be a URL type - Auth0 JDK provider strips string endpoints down to
    * their domain name.
    */
  def jwkUrl: URL =
    new URL(
      s"https://cognito-idp.${region.toString}.amazonaws.com/$id/.well-known/jwks.json"
    )
}

object CognitoConfig {

  def apply(config: Config): CognitoConfig = {

    val region: Region = config
      .as[Option[String]]("cognito.region")
      .map(Region.of(_))
      .getOrElse(Region.US_EAST_1)

    val userPoolId = config.as[String]("cognito.user_pool.id")

    val tokenPoolId = config.as[String]("cognito.token_pool.id")

    val userPoolAppClientId =
      config.as[String]("cognito.user_pool.app_client_id")

    val tokenPoolAppClientId =
      config.as[String]("cognito.token_pool.app_client_id")

    CognitoConfig(
      region = region,
      userPool = CognitoPoolConfig(
        region = region,
        id = userPoolId,
        appClientId = userPoolAppClientId
      ),
      tokenPool = CognitoPoolConfig(
        region = region,
        id = tokenPoolId,
        appClientId = tokenPoolAppClientId
      )
    )
  }
}
