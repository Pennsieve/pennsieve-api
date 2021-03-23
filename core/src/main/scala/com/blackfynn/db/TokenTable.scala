package com.pennsieve.db

import java.time.ZonedDateTime

import com.pennsieve.models.Token
import com.pennsieve.traits.PostgresProfile.api._

final class TokenTable(tag: Tag)
    extends Table[Token](tag, Some("pennsieve"), "tokens") {

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def name = column[String]("name")
  def token = column[String]("token")
  def secret = column[String]("secret")
  def organizationId = column[Int]("organization_id")
  def userId = column[Int]("user_id")
  def lastUsed = column[Option[ZonedDateTime]]("last_used")
  def createdAt =
    column[ZonedDateTime]("created_at", O.AutoInc) // set by the database on insert

  def * =
    (name, token, secret, organizationId, userId, lastUsed, createdAt, id)
      .mapTo[Token]
}

object TokensMapper extends TableQuery(new TokenTable(_)) {
  def getById(id: Int) = this.filter(_.id === id).result.headOption
  def getByToken(token: String) =
    this.filter(_.token === token).result.headOption
  def getByUser(userId: Int) =
    this.filter(_.userId === userId).result.headOption
}
