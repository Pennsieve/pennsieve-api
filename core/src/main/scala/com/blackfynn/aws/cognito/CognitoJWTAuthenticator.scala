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

package com.blackfynn.aws.cognito

import com.auth0.jwk.{GuavaCachedJwkProvider, JwkProvider, UrlJwkProvider}
import io.circe.derivation.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder, Json, Parser, ParsingFailure}
import pdi.jwt.{JwtAlgorithm, JwtCirce, JwtClaim, JwtOptions}
import cats.data._
import cats.implicits._
import com.pennsieve.models.CognitoId

import java.time.Instant
import java.util.UUID
import scala.io.Source.fromURL

/*
trait CognitoClaimType {}

object CognitoClaimType {
  implicit def claimTypeEncoder: Encoder[CognitoClaimType] =
    deriveEncoder[CognitoClaimType]
  implicit def claimTypeDecoder: Decoder[CognitoClaimType] =
    deriveDecoder[CognitoClaimType]
}

case class CognitoClaim(cognitoUsername: String) extends CognitoClaimType {
  def toJwtClaim: JwtClaim = JwtClaim(content = cognitoUsername)
}

object CognitoClaim {
  private def v2Decoder: Decoder[CognitoClaim] = deriveDecoder[CognitoClaim]

  private def v1Decoder: Decoder[CognitoClaim] = new Decoder[CognitoClaim] {
    final def apply(c: HCursor): Decoder.Result[CognitoClaim] =
      for {
        cognitoUsername <- c.downField("cognito:username").as[String]
      } yield CognitoClaim(cognitoUsername = cognitoUsername)
  }

  implicit def cognitoClaimEncoder: Encoder[CognitoClaim] =
    deriveEncoder[CognitoClaim]
  implicit def cognitoClaimDecoder: Decoder[CognitoClaim] =
    List(v2Decoder, v1Decoder).reduceLeft(_ or _)
}
 */

final case class CognitoPayload(id: CognitoId, issuedAt: Instant)

object CognitoPayload {
  /** Create a CognitoPayload from the given claim
    *
    * @param claim The claim to parse into a CognitoPayload
    * @param resourceServer All scopes that are not related to the
    *        given resource server will be filtered out
    */
  def apply(
    claim: JwtClaim,
  ): Either[Throwable, CognitoPayload] =
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
//      claimContent <- io.circe.parser.parse(claim.content)
//      scopes <- claimContent.hcursor
//        .downField("scope")
//        .as[Option[List[(CognitoResourceServerIdentifier, Scope)]]]
//      filteredScopes = scopes.toList.flatten
//        .filter {
//          case (scopeResourceServer, _) =>
//            scopeResourceServer == resourceServer
//          case _ => false
//        }
//        .map { case (_, scope) => scope }
//        .toSet
    } yield CognitoPayload(cognitoId, issuedAtInstant)
}

object CognitoJWTAuthenticator extends Parser {

  def getKeysUrl(awsRegion: String, userPoolId: String): String = {
    s"https://cognito-idp.$awsRegion.amazonaws.com/$userPoolId/.well-known/jwks.json"
  }

  def getJwkProvider(
    awsRegion: String,
    userPoolId: String
  ): GuavaCachedJwkProvider = {
    new GuavaCachedJwkProvider(
      new UrlJwkProvider(getKeysUrl(awsRegion, userPoolId))
    )
  }

  def validateJwt(
    awsRegion: String,
    awsUserPoolId: String,
    awsAppClientIds: String,
    token: String,
    jwkProvider: JwkProvider
  ): Either[Throwable, CognitoPayload] = {
    for {
      keyId <- getKeyId(token)
      jwk <- Either.catchNonFatal(jwkProvider.get(keyId)) // verify the key is within the JWKS
      claim <- JwtCirce
        .decode(token, jwk.getPublicKey, Seq(JwtAlgorithm.RS256)) // performs validation
        .toEither
      _ <- validateClaim(claim, awsRegion, awsUserPoolId, awsAppClientIds)
      payload <- CognitoPayload(claim)
    } yield payload
  }

  /*
   * Parse the JWT without verifying the signature here only to retrieve the
   * header's kid for use in later validation
   */
  private def getKeyId(token: String): Either[Throwable, String] = {
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

  private def getUserPoolEndpoint(
    awsRegion: String,
    awsUserPoolId: String
  ): String = {
    s"https://cognito-idp.$awsRegion.amazonaws.com/$awsUserPoolId"
  }

  private case class CognitoContent(
    client_id: Option[String]
  )

  object CognitoContent {
    implicit def encoder: Encoder[CognitoContent] =
      deriveEncoder[CognitoContent]
    implicit def decoder: Decoder[CognitoContent] =
      deriveDecoder[CognitoContent]
  }

  /*
   * Verify the issuer and audience in the JWT match what was expected
   *
   * The dropRight on the configuration User Pool endpoint removes a trailing
   * slash which the issuer might not contain
   */
  private def validateClaim(
    claim: JwtClaim,
    awsRegion: String,
    awsUserPoolId: String,
    awsAppClientIds: String
  ): Either[Throwable, Unit] =
    decode[CognitoContent](claim.content).flatMap { content =>
      (
        claim.issuer.contains(getUserPoolEndpoint(awsRegion, awsUserPoolId)) || claim.issuer.contains(
          getUserPoolEndpoint(awsRegion, awsUserPoolId).dropRight(1)
        ),
        (content.client_id.exists(a => a == awsAppClientIds) || claim.audience
          .exists(audiences => audiences == awsAppClientIds))
      ) match {
        case (false, _) => Left(new Exception("claim contains invalid issuer"))
        case (_, false) => {
          val audiences = claim.audience
            .getOrElse(Set.empty)
            .union(content.client_id.toSet)
            .mkString(", ")
          Left(new Exception("claim contains invalid audience"))
        }
        case _ => Right(())
      }
    }

  /*
  def getUserIdFromToken(token: Jwt.Token): Either[Throwable, String] = {
    for {
      claim <- JwtCirce
        .decode(token.value, "jwt-key", Seq(JwtAlgorithm.HS256))
        .toEither
      content <- decode[CognitoClaim](claim.content)
    } yield {
      content.cognitoUsername

      // TODO: now take the above and get Blackfynn ID from DB
    }
  }
   */

  override def parse(input: String): Either[ParsingFailure, Json] = ???
}
