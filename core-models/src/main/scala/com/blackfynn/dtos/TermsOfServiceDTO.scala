package com.pennsieve.dtos

import com.pennsieve.models.DateVersion

import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.{ Decoder, Encoder }
import io.circe.java8.time._

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

case class CustomTermsOfServiceDTO(version: String, organizationId: String)

object CustomTermsOfServiceDTO {
  def apply(
    dateVersion: ZonedDateTime,
    organizationId: String
  ): CustomTermsOfServiceDTO = CustomTermsOfServiceDTO(
    dateVersion.format(DateTimeFormatter.ofPattern(DateVersion.format)),
    organizationId
  )

  implicit val encoder: Encoder[CustomTermsOfServiceDTO] =
    deriveEncoder[CustomTermsOfServiceDTO]
  implicit val decoder: Decoder[CustomTermsOfServiceDTO] =
    deriveDecoder[CustomTermsOfServiceDTO]

}

case class PennsieveTermsOfServiceDTO(version: String)

object PennsieveTermsOfServiceDTO {
  def apply(dateVersion: ZonedDateTime): PennsieveTermsOfServiceDTO =
    PennsieveTermsOfServiceDTO(
      dateVersion.format(DateTimeFormatter.ofPattern(DateVersion.format))
    )

  implicit val encoder: Encoder[PennsieveTermsOfServiceDTO] =
    deriveEncoder[PennsieveTermsOfServiceDTO]
  implicit val decoder: Decoder[PennsieveTermsOfServiceDTO] =
    deriveDecoder[PennsieveTermsOfServiceDTO]

}
