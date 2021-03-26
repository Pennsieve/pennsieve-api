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
import com.pennsieve.core.utilities.checkOrError
import com.pennsieve.domain.{
  CoreError,
  InvalidJWT,
  ParseError,
  ThrowableError
}
import com.pennsieve.models.CognitoId

import java.time.Instant
import java.util.UUID

final case class CognitoPayload(id: CognitoId, issuedAt: Instant)

object CognitoPayload {

  /** Create a CognitoPayload from the given claim
    *
    * @param claim The claim to parse into a CognitoPayload
    * @param resourceServer All scopes that are not related to the
    *        given resource server will be filtered out
    */
  def apply(claim: JwtClaim): Either[CoreError, CognitoPayload] =
    for {
      subject <- Either.fromOption(
        claim.subject,
        InvalidJWT(claim.content, Some("JWT claim missing 'sub' field"))
      )
      cognitoId = CognitoId(UUID.fromString(subject))
      issuedAt <- Either.fromOption(
        claim.issuedAt,
        InvalidJWT(claim.content, Some("JWT claim missing 'iat' field"))
      )
      issuedAtInstant <- Either
        .catchNonFatal(Instant.ofEpochSecond(issuedAt))
        .leftMap(ThrowableError(_))
    } yield CognitoPayload(cognitoId, issuedAtInstant)
}

object CognitoJWTAuthenticator {

  case class CognitoContent(client_id: Option[String])

  object CognitoContent {
    implicit def encoder: Encoder[CognitoContent] =
      deriveEncoder[CognitoContent]
    implicit def decoder: Decoder[CognitoContent] =
      deriveDecoder[CognitoContent]
  }

  def getJwkProvider(poolConfig: CognitoPoolConfig): GuavaCachedJwkProvider =
    new GuavaCachedJwkProvider(new UrlJwkProvider(poolConfig.jwkUrl))

  def validateJwt(
    token: String
  )(implicit
    cognitoConfig: CognitoConfig
  ): Either[CoreError, CognitoPayload] = {
    for {
      keyId <- getKeyId(token)
      jwk <- Either
        .catchNonFatal(cognitoConfig.jwkProvider.get(keyId))
        .leftMap(ThrowableError(_))
      claim <- JwtCirce
        .decode(token, jwk.getPublicKey, Seq(JwtAlgorithm.RS256))
        .toEither
        .leftMap(_ => InvalidJWT(token))
      _ <- validateClaim(claim)
      payload <- CognitoPayload(claim)
    } yield payload
  }

  /*
   * Parse the JWT without verifying the signature here only to retrieve the
   * header's kid for use in later validation
   */
  def getKeyId(token: String): Either[CoreError, String] = {
    JwtCirce
      .decodeAll(token, options = JwtOptions(signature = false))
      .toEither
      .leftMap(_ => InvalidJWT(token))
      .flatMap {
        case (header, _, _) =>
          Either.fromOption(
            header.keyId,
            InvalidJWT(token, Some("kid not present in JWT header"))
          )
      }
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
  ): Either[CoreError, Unit] =
    for {
      content <- decode[CognitoContent](claim.content).leftMap(ParseError(_))

      _ <- checkOrError(claim.issuer.contains(cognitoConfig.userPool.endpoint))(
        InvalidJWT(claim.content, Some("claim contains invalid issuer"))
      )

      _ <- checkOrError(
        content.client_id.exists(_ == cognitoConfig.userPool.appClientId)
          || claim.audience
            .exists(_.contains(cognitoConfig.userPool.appClientId))
      )(InvalidJWT(claim.content, Some("claim contains invalid audience")))

    } yield ()
}
