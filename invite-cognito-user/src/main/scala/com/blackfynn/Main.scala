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

package com.pennsieve.utilities.`invite-cognito-users`

import cats.data._
import cats.implicits._
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.utilities._
import com.pennsieve.core.utilities._
import com.pennsieve.db._
import com.pennsieve.aws.cognito._
import com.pennsieve.aws.email._
import com.pennsieve.aws.ssm._
import com.typesafe.config.{ Config, ConfigFactory, ConfigValueFactory }
import net.ceedubs.ficus.Ficus._
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagementAsyncClientBuilder
import com.amazonaws.regions.Regions
import software.amazon.awssdk.services.cognitoidentityprovider.model._
import sys.process._

import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import scala.compat.java8.FutureConverters._
import scala.util.Try

case class Awaitable[A](f: Future[A]) {
  def await: A = Await.result(f, Duration.Inf)

  def awaitFinite(wait: FiniteDuration = 5.seconds): A = Await.result(f, wait)
}

object AwaitableImplicits {
  implicit def toAwaitable[A](f: Future[A]): Awaitable[A] = Awaitable(f)

  implicit def toAwaitable[A, B](
    e: EitherT[Future, A, B]
  ): Awaitable[Either[A, B]] = Awaitable(e.value)
}

import AwaitableImplicits._

class InviteContainer(val config: Config)
    extends Container
    with DatabaseContainer
    with UserManagerContainer {}

object Main extends App {

  val environment: String = ConfigFactory.load().as[String]("environment")

  lazy val ssm: AWSSimpleSystemsManagement =
    new AWSSimpleSystemsManagement(
      AWSSimpleSystemsManagementAsyncClientBuilder
        .standard()
        .withRegion(Regions.US_EAST_1)
        .build()
    )

  val config: Config = ConfigFactory
    .load()
    .withFallback(
      ssm
        .getParametersAsConfig(
          Map(
            // Core Postgres
            s"/$environment/api/pennsieve-postgres-host" -> "postgres.host",
            s"/$environment/api/pennsieve-postgres-port" -> "postgres.port",
            s"/$environment/api/pennsieve-postgres-database" -> "postgres.database",
            s"/$environment/api/pennsieve-postgres-user" -> "postgres.user",
            s"/$environment/api/pennsieve-postgres-password" -> "postgres.password",
            // Cognito
            s"/$environment/admin/cognito-user-pool-id" -> "cognito.user_pool.id",
            s"/$environment/admin/cognito-user-pool-app-client-id" -> "cognito.user_pool.app_client_id",
            s"/$environment/admin/cognito-token-pool-id" -> "cognito.token_pool.id",
            s"/$environment/admin/cognito-token-pool-app-client-id" -> "cognito.token_pool.app_client_id"
          ),
          withDecryption = true
        )
        .awaitFinite()
    )

  val emails =  config.as[String]("emails").split(",").map(_.trim.toLowerCase)

  val jumpbox: String = config.as[String]("jumpbox")

  val pennPort = 1113
  val pennTunnel =
    Process(
      Seq(
        "ssh",
        "-o",
        "ExitOnForwardFailure=yes",
        "-L",
        s"${pennPort}:${config.as[String]("postgres.host")}:${config.as[String]("postgres.port")}",
        jumpbox,
        "sleep",
        "30"
      )
    ).run()
  sys.addShutdownHook(pennTunnel.destroy)

  Thread.sleep(1000)
  if (!pennTunnel.isAlive())
    throw new Exception("Tunnel died")

  val cognito: CognitoClient = Cognito(config)

  val container = new InviteContainer(
    config
      .withValue("postgres.host", ConfigValueFactory.fromAnyRef("localhost"))
      .withValue("postgres.port", ConfigValueFactory.fromAnyRef(pennPort))
  )

  for (email <- emails) {
    println(s"Inviting user ${email}")

    val user =
      container.userManager
        .getByEmail(email)
        .awaitFinite()
        .fold(throw _, u => u)

    val maybeCognitoUser = container.db
      .run(CognitoUserMapper.filter(_.userId === user.id).result)
      .awaitFinite()
      .headOption

    maybeCognitoUser match {
      case Some(cognitoUser) =>
        println(
          s"Cognito user ${cognitoUser.cognitoId} already exists for $email"
        )
        cognitoUser

      case None =>
        println(s"Inviting new Cognito user for $email...")

        val cognitoId = cognito
          .inviteUser(Email(email), suppressEmail = true)
          .awaitFinite()

        // TODO: remove this, use CSV import?
        cognito
          .asInstanceOf[Cognito]
          .client
          .adminSetUserPassword(
            AdminSetUserPasswordRequest
              .builder()
              .userPoolId(
                cognito.asInstanceOf[Cognito].cognitoConfig.userPool.id
              )
              .username(email)
              .password(s"${UUID.randomUUID()}aA1@")
              .permanent(true)
              .build
          )
          .toScala
          .awaitFinite()

        val cognitoUser = container.db
          .run(CognitoUserMapper.create(cognitoId, user))
          .awaitFinite()

        println(s"Created new Cognito user ${cognitoUser.cognitoId} for $email")

      // TODO: send welcome email
    }

    println("Done. Users must reset password with 'Forgot Password' flow.")
  }

  sys.exit(0)
}
