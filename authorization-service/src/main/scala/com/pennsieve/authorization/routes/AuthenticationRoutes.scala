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

import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.pennsieve.aws.cognito.{ CognitoConfig, CognitoPoolConfig }
import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import com.pennsieve.akka.http.RouteService
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

class AuthenticationRoutes(cognitoConfig: CognitoConfig) extends RouteService {
  val routes: Route = pathPrefix("authentication") { configRoute }

  case class CognitoPoolConfigDTO(
    region: String,
    id: String,
    appClientId: String
  )

  case class CognitoConfigDTO(
    region: String,
    userPool: CognitoPoolConfigDTO,
    tokenPool: CognitoPoolConfigDTO
  )

  object CognitoConfigDTO {
    implicit val encoder: Encoder[CognitoConfigDTO] =
      deriveEncoder[CognitoConfigDTO]
    implicit val decoder: Decoder[CognitoConfigDTO] =
      deriveDecoder[CognitoConfigDTO]

    def apply(cognitoConfig: CognitoConfig): CognitoConfigDTO = {
      CognitoConfigDTO(
        region = cognitoConfig.region.toString,
        userPool = CognitoPoolConfigDTO(cognitoConfig.userPool),
        tokenPool = CognitoPoolConfigDTO(cognitoConfig.tokenPool)
      )
    }
  }

  object CognitoPoolConfigDTO {
    implicit val encoder: Encoder[CognitoPoolConfigDTO] =
      deriveEncoder[CognitoPoolConfigDTO]
    implicit val decoder: Decoder[CognitoPoolConfigDTO] =
      deriveDecoder[CognitoPoolConfigDTO]

    def apply(cognitoPoolConfig: CognitoPoolConfig): CognitoPoolConfigDTO = {
      CognitoPoolConfigDTO(
        region = cognitoPoolConfig.region.toString,
        id = cognitoPoolConfig.id,
        appClientId = cognitoPoolConfig.appClientId
      )
    }
  }

  /**
    * Expose up-to-date Cognito client application for clients to use when
    * in their authentication flows.
    */
  def configRoute: Route =
    (path("cognito-config") & get) {
      complete((OK, CognitoConfigDTO(cognitoConfig)))
    }
}
