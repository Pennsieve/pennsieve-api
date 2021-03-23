// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.managers

import cats.data._
import cats.implicits._
import com.pennsieve.domain.Sessions._
import com.pennsieve.models.{ Organization, Token, User }
import com.pennsieve.core.utilities.checkOrError
import com.pennsieve.domain.{
  CoreError,
  Error,
  KeyValueStoreError,
  NotFound,
  ParseError
}
import io.circe.generic.auto._
import io.circe.parser.decode
import io.circe.syntax._
import io.github.nremond.SecureHash

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

class SessionManager(redisManager: RedisManager, userManager: UserManager) {

  def update(session: Session): Either[CoreError, String] = {
    val sessionJSON: String = session.asJson.noSpaces
    val result: Boolean = redisManager.set(session.key, sessionJSON)

    if (result) Right("saved session")
    else Left(KeyValueStoreError(session.key, sessionJSON))
  }

  def create(
    user: User,
    sessionType: SessionType,
    ttl: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Session] =
    for {
      organization <- userManager.getPreferredOrganization(user)
      session <- create(user, organization, sessionType, ttl)
    } yield session

  def create(
    user: User,
    organization: Organization,
    sessionType: SessionType,
    ttl: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Session] = {
    val session: Session = Session(user, sessionType, organization)
    val result: Boolean =
      redisManager.set(session.key, session.asJson.noSpaces, ttl)

    checkOrError[CoreError](result)(KeyValueStoreError(session.key, session))
      .toEitherT[Future]
      .map(_ => session)
  }

  def get(uuid: String): Either[CoreError, Session] = {
    val key: String = sessionKey(uuid)

    redisManager
      .get(key)
      .toRight(NotFound(key))
      .flatMap { json =>
        decode[Session](json).leftMap(ParseError)
      }
  }

  def remove(session: Session): Either[CoreError, Unit] = remove(session.uuid)

  def remove(uuid: String): Either[CoreError, Unit] = {
    val key: String = sessionKey(uuid)
    val result: Boolean = redisManager.del(key)

    if (result) Right(())
    else Left(NotFound(key))
  }

  def generateBrowserSession(
    user: User,
    ttl: Int = 600
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Session] =
    create(user, BrowserSession, ttl)

  def generateTemporarySession(
    user: User,
    ttl: Int = 300
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Session] =
    create(user, TemporarySession, ttl)

  def generateAPISession(
    token: Token,
    ttl: Int = 1200,
    tokenManager: TokenManager
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Session] =
    for {
      user <- userManager.get(token.userId)
      organization <- tokenManager.getOrganization(token)
      session <- create(user, organization, APISession(token.token), ttl)
    } yield session

  def validateSecret(
    principal: String,
    clearTextPassword: String,
    hashedSecret: String
  ): Either[Error, String] = {
    if (SecureHash.validatePassword(clearTextPassword, hashedSecret)) {
      redisManager.hdel("badLoginCount", principal)

      Right(principal)
    } else {
      redisManager.hinc("badLoginCount", principal)

      Left(Error("Invalid token or secret"))
    }
  }

  def badLoginCount(principal: String): Int = {
    Try {
      redisManager
        .hget("badLoginCount", principal)
        .getOrElse("0")
        .toInt
    }.toOption.getOrElse(0)
  }

}
