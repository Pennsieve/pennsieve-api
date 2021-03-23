// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.dtos

import com.pennsieve.models.{ Contributor, Degree, User }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.{ Decoder, Encoder }

final case class ContributorDTO(
  id: Int,
  firstName: String,
  middleInitial: Option[String],
  lastName: String,
  degree: Option[Degree],
  email: String,
  orcid: Option[String],
  userId: Option[Int]
) {
  def givenName: String = {
    s"$firstName ${middleInitial.getOrElse("")}".trim
  }
}

object ContributorDTO {

  implicit val encoder: Encoder[ContributorDTO] = deriveEncoder[ContributorDTO]
  implicit val decoder: Decoder[ContributorDTO] = deriveDecoder[ContributorDTO]

  def apply(contributorAndUser: (Contributor, Option[User])): ContributorDTO =
    ContributorDTO(contributorAndUser._1, contributorAndUser._2)

  def apply(contributor: Contributor, user: Option[User]): ContributorDTO = {
    user match {
      case Some(user) =>
        val orcid =
          user.orcidAuthorization.map(_.orcid).orElse(contributor.orcid)
        val degree =
          user.degree.orElse(contributor.degree)
        val middleInitial =
          user.middleInitial.orElse(contributor.middleInitial)

        ContributorDTO(
          id = contributor.id,
          firstName = user.firstName,
          middleInitial = middleInitial,
          lastName = user.lastName,
          degree = degree,
          email = user.email,
          orcid = orcid,
          userId = Some(user.id)
        )
      // since we do not let a contributor have empty first name, last name and
      // email, we actually never get into the situation where we need the value
      // from the getOrElse
      case _ =>
        ContributorDTO(
          id = contributor.id,
          firstName = contributor.firstName.getOrElse(""),
          middleInitial = contributor.middleInitial,
          lastName = contributor.lastName.getOrElse(""),
          degree = contributor.degree,
          email = contributor.email.getOrElse(""),
          orcid = contributor.orcid,
          userId = None
        )
    }

  }
}
