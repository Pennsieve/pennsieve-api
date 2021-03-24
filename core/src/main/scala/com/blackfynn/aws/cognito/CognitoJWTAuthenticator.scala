package com.blackfynn.aws.cognito

import com.blackfynn.auth.middleware.Jwt
import io.circe.{Decoder, Encoder, HCursor, Json, Parser, ParsingFailure}
import io.circe.derivation.deriveDecoder
import io.circe.generic.extras.semiauto.deriveEncoder
import pdi.jwt.{JwtAlgorithm, JwtCirce, JwtClaim}

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

object CognitoJWTAuthenticator extends Parser {

  var keysJson: String = ""

  def getKeysUrl(awsRegion: String, userPoolId: String): String = {
    s"https://cognito-idp.$awsRegion.amazonaws.com/$userPoolId/.well-known/jwks.json"
  }

  def getKeysJson(awsRegion: String, userPoolId: String): String = {
    if (keysJson != "") {
      return keysJson
    }

    keysJson = fromURL(getKeysUrl(awsRegion, userPoolId)).mkString

    return keysJson
  }

  def validateToken(token: String): Either[Throwable, Boolean] = {
    for {
      claims <- JwtCirce
        .decode(token)
        .toEither
    } yield {
      true
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
