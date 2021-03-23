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

package com.pennsieve.test.helpers

import cats.data.EitherT
import com.pennsieve.core.utilities.PostgresDatabase

import scala.concurrent.Future
import scala.concurrent.Await
import scala.concurrent.duration._
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.migrations.DatabaseMigrationRunner
import slick.jdbc.GetResult

case class Awaitable[A](f: Future[A]) {
  def await: A = Await.result(f, Duration.Inf)
  def awaitFinite(duration: Duration = 5.seconds): A = Await.result(f, duration)
}

trait AwaitableImplicits {
  implicit def toAwaitable[A, B](
    e: EitherT[Future, A, B]
  ): Awaitable[Either[A, B]] = Awaitable(e.value)
  implicit def toAwaitable[A](f: Future[A]): Awaitable[A] = Awaitable(f)
}

object AwaitableImplicits extends AwaitableImplicits

trait TestDatabase extends AwaitableImplicits {

  def createSchema(schema: String): DBIO[Unit] =
    DBIO.seq(sqlu"""CREATE SCHEMA IF NOT EXISTS "#$schema"""")

  def dropOrganizationSchema(schema: String): DBIO[Unit] = {
    DBIO.seq(sqlu"""DROP SCHEMA IF EXISTS "#$schema" CASCADE""")
  }

  implicit val getUnitResult: GetResult[Unit] = GetResult(_ => ())

  def migrateCoreSchema(postgresDB: PostgresDatabase): Unit = {

    val runner = new DatabaseMigrationRunner(
      postgresDB.jdbcURL,
      postgresDB.user,
      postgresDB.password
    )

    runner.migrateCoreSchema()
  }

  def migrateOrganizationSchema(
    organizationId: Int,
    postgresDB: PostgresDatabase
  ): Unit = {

    val runner = new DatabaseMigrationRunner(
      postgresDB.jdbcURL,
      postgresDB.user,
      postgresDB.password
    )

    runner.migrateOrganizationSchema(organizationId)
    runner.refreshUnionView
  }

  // Used to clear the tables in the test postgres database
  def clearDB: DBIO[Unit] = DBIO.seq(
    // clears organizations, subscriptions, and feature flags due to their foreign key relationships
    sqlu"""TRUNCATE TABLE "pennsieve"."cognito_users" RESTART IDENTITY CASCADE""",
    sqlu"""TRUNCATE TABLE "pennsieve"."users" RESTART IDENTITY CASCADE""",
    sqlu"""TRUNCATE TABLE "pennsieve"."organizations" RESTART IDENTITY CASCADE""",
    sqlu"""TRUNCATE TABLE "pennsieve"."teams" RESTART IDENTITY CASCADE""",
    sqlu"""TRUNCATE TABLE "pennsieve"."organization_team" RESTART IDENTITY CASCADE""",
    sqlu"""TRUNCATE TABLE "pennsieve"."organization_user" RESTART IDENTITY CASCADE""",
    sqlu"""TRUNCATE TABLE "pennsieve"."team_user" RESTART IDENTITY CASCADE""",
    sqlu"""TRUNCATE TABLE "pennsieve"."user_invite" RESTART IDENTITY CASCADE""",
    sqlu"""TRUNCATE TABLE "pennsieve"."tokens" RESTART IDENTITY CASCADE"""
  )

  // Used to clear the tables in the test postgres database in an organization's schema
  def clearOrganizationSchema(organizationId: Int): DBIO[Unit] = {
    val schema: String = organizationId.toString

    DBIO.seq(
      sqlu"""TRUNCATE TABLE "#$schema"."datasets" RESTART IDENTITY CASCADE""",
      sqlu"""TRUNCATE TABLE "#$schema"."packages" RESTART IDENTITY CASCADE""",
      sqlu"""TRUNCATE TABLE "#$schema"."annotations" RESTART IDENTITY CASCADE""",
      sqlu"""TRUNCATE TABLE "#$schema"."annotation_layers" RESTART IDENTITY CASCADE""",
      sqlu"""TRUNCATE TABLE "#$schema"."discussions" RESTART IDENTITY CASCADE""",
      sqlu"""TRUNCATE TABLE "#$schema"."comments" RESTART IDENTITY CASCADE""",
      sqlu"""TRUNCATE TABLE "#$schema"."dataset_status" RESTART IDENTITY CASCADE""",
      sqlu"""TRUNCATE TABLE "#$schema"."contributors" RESTART IDENTITY CASCADE""",
      sqlu"""TRUNCATE TABLE "#$schema"."dataset_contributor" RESTART IDENTITY CASCADE""",
      sqlu"""TRUNCATE TABLE "#$schema"."collections" RESTART IDENTITY CASCADE""",
      sqlu"""TRUNCATE TABLE "#$schema"."data_use_agreements" RESTART IDENTITY CASCADE"""
    )
  }

  def clearDBSchema: DBIO[Unit] = DBIO.seq(
    sqlu"""TRUNCATE TABLE "timeseries"."ranges" RESTART IDENTITY CASCADE""",
    sqlu"""TRUNCATE TABLE "timeseries"."annotations" RESTART IDENTITY CASCADE""",
    sqlu"""TRUNCATE TABLE "timeseries"."channel_groups" RESTART IDENTITY CASCADE""",
    sqlu"""TRUNCATE TABLE "timeseries"."layers" RESTART IDENTITY CASCADE""",
    sqlu"""TRUNCATE TABLE "timeseries"."layers" RESTART IDENTITY CASCADE"""
  )

}
