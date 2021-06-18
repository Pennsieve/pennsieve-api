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

package com.pennsieve.db

import com.pennsieve.models._
import com.pennsieve.traits.PostgresProfile.api._
import java.time.ZonedDateTime

import cats.Semigroup
import cats.implicits._
import java.util.UUID

import com.pennsieve.domain.SqlError

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
