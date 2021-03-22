// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.db

import com.blackfynn.models._
import com.blackfynn.traits.PostgresProfile.api._
import java.time.ZonedDateTime

import cats.Semigroup
import cats.implicits._
import java.util.UUID

import com.blackfynn.domain.SqlError

import scala.concurrent.ExecutionContext

final class ContributorTable(schema: String, tag: Tag)
    extends Table[Contributor](tag, Some(schema), "contributors") {

  // set by the database
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def firstName = column[Option[String]]("first_name")
  def lastName = column[Option[String]]("last_name")
  def middleInitial = column[Option[String]]("middle_initial")
  def degree = column[Option[Degree]]("degree")
  def email = column[Option[String]]("email")
  def orcid = column[Option[String]]("orcid")
  def userId = column[Option[Int]]("user_id")
  def createdAt = column[ZonedDateTime]("created_at", O.AutoInc)
  def updatedAt = column[ZonedDateTime]("updated_at", O.AutoInc)

  def * =
    (
      firstName,
      lastName,
      middleInitial,
      degree,
      email,
      orcid,
      userId,
      createdAt,
      updatedAt,
      id
    ).mapTo[Contributor]
}

class ContributorMapper(val organization: Organization)
    extends TableQuery(new ContributorTable(organization.schemaId, _)) {

  def get(id: Int): Query[ContributorTable, Contributor, Seq] =
    this.filter(_.id === id)

  def getByUserId(userId: Int): Query[ContributorTable, Contributor, Seq] =
    this.filter(_.userId === userId)

  def getContributors(): Query[
    (ContributorTable, Rep[Option[UserTable]]),
    (Contributor, Option[User]),
    Seq
  ] =
    this
      .joinLeft(UserMapper)
      .on(_.userId === _.id)

  def getContributor(id: Int): Query[
    (ContributorTable, Rep[Option[UserTable]]),
    (Contributor, Option[User]),
    Seq
  ] = {

    this
      .get(id)
      .joinLeft(UserMapper)
      .on(_.userId === _.id)
  }

  def getContributorByUserId(userId: Int): Query[
    (ContributorTable, Rep[Option[UserTable]]),
    (Contributor, Option[User]),
    Seq
  ] =
    this
      .getByUserId(userId)
      .joinLeft(UserMapper)
      .on(_.userId === _.id)

  def getContributorByEmail(email: String): Query[
    (ContributorTable, Rep[Option[UserTable]]),
    (Contributor, Option[User]),
    Seq
  ] =
    this
      .joinLeft(UserMapper)
      .on(_.userId === _.id)
      .filter {
        case (contributor, user) =>
          contributor.email.toLowerCase === email.toLowerCase || user
            .map(_.email)
            .toLowerCase === email.toLowerCase
      }

}
