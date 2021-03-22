package com.blackfynn.aws.cognito

import com.blackfynn.auth.middleware.{Jwt}
import io.circe.{Decoder, Encoder, HCursor}
import io.circe.derivation.deriveDecoder
import io.circe.generic.extras.semiauto.deriveEncoder
import pdi.jwt.{JwtAlgorithm, JwtCirce}

sealed trait CognitoClaimType {
}

object CognitoClaimType {
  implicit def claimTypeEncoder: Encoder[CognitoClaimType] = deriveEncoder[CognitoClaimType]
  implicit def claimTypeDecoder: Decoder[CognitoClaimType] = deriveDecoder[CognitoClaimType]
}

case class CognitoClaim(
  cognitoUsername: String
) extends CognitoClaimType

object CognitoClaim {
  private def v2Decoder: Decoder[CognitoClaim] = deriveDecoder[CognitoClaim]

  private def v1Decoder: Decoder[CognitoClaim] = new Decoder[CognitoClaim] {
    final def apply(c: HCursor): Decoder.Result[CognitoClaim] =
      for {
        cognitoUsername <- c.downField("cognito:username").as[String]
      } yield
        CognitoClaim(
          cognitoUsername = cognitoUsername
        )
  }

  implicit def cognitoClaimEncoder: Encoder[CognitoClaim] = deriveEncoder[CognitoClaim]
  implicit def cognitoClaimDecoder: Decoder[CognitoClaim] = List(v2Decoder, v1Decoder).reduceLeft(_ or _)
}

object CognitoJWTAuthenticator {

  def validateToken(token: Jwt.Token): Boolean = {
    false
  }

  def getUserIdFromToken(token: Jwt.Token): Either[Throwable, String] = {
    for {
      claim <- JwtCirce
        .decode(token.value, "jwt-key", Seq(JwtAlgorithm.HS256))
        .toEither
    } yield
      claim.content
  }
}
