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

import com.auth0.jwk.{ GuavaCachedJwkProvider, UrlJwkProvider }
import io.circe.derivation.{ deriveDecoder, deriveEncoder }
import io.circe.{ Decoder, Encoder }
import io.circe.parser.decode
import pdi.jwt.{ JwtAlgorithm, JwtCirce, JwtClaim, JwtOptions }
import cats.implicits._
import com.pennsieve.models.CognitoId

import java.time.Instant
import java.util.UUID
import java.net.URL

final case class CognitoPayload(id: CognitoId, issuedAt: Instant)

object CognitoPayload {

  /** Create a CognitoPayload from the given claim
    *
    * @param claim The claim to parse into a CognitoPayload
    * @param resourceServer All scopes that are not related to the
    *        given resource server will be filtered out
    */
  def apply(claim: JwtClaim): Either[Throwable, CognitoPayload] =
    for {
      subject <- Either.fromOption(
        claim.subject,
        new Throwable("JWT claim missing 'sub' field")
      )
      cognitoId = CognitoId(UUID.fromString(subject))
      issuedAt <- Either.fromOption(
        claim.issuedAt,
        new Throwable("JWT claim missing 'iat' field:")
      )
      issuedAtInstant <- Either.catchNonFatal(Instant.ofEpochSecond(issuedAt))
    } yield CognitoPayload(cognitoId, issuedAtInstant)
}

object CognitoJWTAuthenticator {

  def getJwkProvider(poolConfig: CognitoPoolConfig): GuavaCachedJwkProvider =
    new GuavaCachedJwkProvider(new UrlJwkProvider(poolConfig.jwkUrl))

  def validateJwt(
    token: String
  )(implicit
    cognitoConfig: CognitoConfig
  ): Either[Throwable, CognitoPayload] = {
    for {
      keyId <- getKeyId(token)
      jwk <- Either.catchNonFatal(cognitoConfig.jwkProvider.get(keyId))
      claim <- JwtCirce
        .decode(token, jwk.getPublicKey, Seq(JwtAlgorithm.RS256))
        .toEither
      _ <- validateClaim(claim)
      payload <- CognitoPayload(claim)
    } yield payload
  }

  /*
   * Parse the JWT without verifying the signature here only to retrieve the
   * header's kid for use in later validation
   */
  def getKeyId(token: String): Either[Throwable, String] = {
    JwtCirce
      .decodeAll(token, options = JwtOptions(signature = false))
      .toEither
      .flatMap {
        case (header, _, _) =>
          Either.fromOption(
            header.keyId,
            new Exception("kid not present in JWT header")
          )
      }
  }

  case class CognitoContent(client_id: Option[String])

  object CognitoContent {
    implicit def encoder: Encoder[CognitoContent] =
      deriveEncoder[CognitoContent]
    implicit def decoder: Decoder[CognitoContent] =
      deriveDecoder[CognitoContent]
  }

  /*
   * Verify the issuer and audience in the JWT match what was expected
   *
   * TODO: check token pool endpoint / client IDs
   */
  private def validateClaim(
    claim: JwtClaim
  )(implicit
    cognitoConfig: CognitoConfig
  ): Either[Throwable, Unit] =
    decode[CognitoContent](claim.content).flatMap { content =>
      (
        claim.issuer.contains(cognitoConfig.userPool.endpoint),
        content.client_id.exists(_ == cognitoConfig.userPool.appClientId)
          || claim.audience
            .exists(_.contains(cognitoConfig.userPool.appClientId))
      ) match {
        case (false, _) => Left(new Exception("claim contains invalid issuer"))
        case (_, false) => {
          Left(new Exception("claim contains invalid audience"))
        }
        case _ => Right(())
      }
    }

}
