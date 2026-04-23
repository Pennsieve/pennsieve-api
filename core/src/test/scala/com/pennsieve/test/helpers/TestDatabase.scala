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

import scala.concurrent.Future
import scala.concurrent.Await
import scala.concurrent.duration._
import com.pennsieve.traits.PostgresProfile.api._
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

  // Used to clear the tables in the test postgres database.
  //
  // Also truncates the pre-seeded organization schemas (1..10) baked into
  // the pennsievedb-seed image. Tests manage their own fixtures, so any seed
  // rows must be cleared before each test regardless of which harness is
  // running. Keep this list in sync with ORGANIZATION_SCHEMA_COUNT in
  // pennsieve-db-migrations/scripts/build-postgres.sh.
  val seededOrganizationIds: Seq[Int] = 1 to 10

  def clearDB: DBIO[Unit] = DBIO.seq(
    Seq(
      // clears organizations, subscriptions, and feature flags due to their foreign key relationships
      sqlu"""TRUNCATE TABLE "pennsieve"."users" RESTART IDENTITY CASCADE""",
      sqlu"""TRUNCATE TABLE "pennsieve"."organizations" RESTART IDENTITY CASCADE""",
      sqlu"""TRUNCATE TABLE "pennsieve"."teams" RESTART IDENTITY CASCADE""",
      sqlu"""TRUNCATE TABLE "pennsieve"."organization_team" RESTART IDENTITY CASCADE""",
      sqlu"""TRUNCATE TABLE "pennsieve"."organization_user" RESTART IDENTITY CASCADE""",
      sqlu"""TRUNCATE TABLE "pennsieve"."team_user" RESTART IDENTITY CASCADE""",
      sqlu"""TRUNCATE TABLE "pennsieve"."user_invite" RESTART IDENTITY CASCADE""",
      sqlu"""TRUNCATE TABLE "pennsieve"."tokens" RESTART IDENTITY CASCADE"""
    ) ++ seededOrganizationIds.map(clearOrganizationSchema): _*
  )

  // Used to clear the tables in the test postgres database in an organization's schema
  def clearOrganizationSchema(organizationId: Int): DBIO[Unit] = {
    val schema: String = organizationId.toString

    // NB: do NOT truncate dataset_status — the seed image ships with the 4
    // default statuses (NO_STATUS, WORK_IN_PROGRESS, IN_REVIEW, COMPLETED)
    // that DatasetManager.create relies on via getDefaultStatus. Tests that
    // need to reset it explicitly call DatasetStatusManager.resetDefaultStatusOptions.
    // Because of that, datacanvases must be truncated explicitly: previously
    // it was cleaned via CASCADE from dataset_status, and leaving rows behind
    // blocks resetDefaultStatusOptions (datacanvases.status_id FK).
    DBIO.seq(
      sqlu"""TRUNCATE TABLE "#$schema"."datasets" RESTART IDENTITY CASCADE""",
      sqlu"""TRUNCATE TABLE "#$schema"."packages" RESTART IDENTITY CASCADE""",
      sqlu"""TRUNCATE TABLE "#$schema"."annotations" RESTART IDENTITY CASCADE""",
      sqlu"""TRUNCATE TABLE "#$schema"."annotation_layers" RESTART IDENTITY CASCADE""",
      sqlu"""TRUNCATE TABLE "#$schema"."contributors" RESTART IDENTITY CASCADE""",
      sqlu"""TRUNCATE TABLE "#$schema"."dataset_contributor" RESTART IDENTITY CASCADE""",
      sqlu"""TRUNCATE TABLE "#$schema"."collections" RESTART IDENTITY CASCADE""",
      sqlu"""TRUNCATE TABLE "#$schema"."data_use_agreements" RESTART IDENTITY CASCADE""",
      sqlu"""TRUNCATE TABLE "#$schema"."datacanvases" RESTART IDENTITY CASCADE"""
    )
  }

  def clearDBSchema: DBIO[Unit] = DBIO.seq(
    sqlu"""TRUNCATE TABLE "timeseries"."ranges" RESTART IDENTITY CASCADE""",
    sqlu"""TRUNCATE TABLE "timeseries"."annotations" RESTART IDENTITY CASCADE""",
    sqlu"""TRUNCATE TABLE "timeseries"."channel_groups" RESTART IDENTITY CASCADE""",
    sqlu"""TRUNCATE TABLE "timeseries"."layers" RESTART IDENTITY CASCADE""",
    sqlu"""TRUNCATE TABLE "timeseries"."layers" RESTART IDENTITY CASCADE"""
  )

  // The seed image pre-creates pennsieve.all_files but not the corresponding
  // union views for other per-organization tables (e.g. datacanvases). Rebuild
  // them once against the pre-seeded schemas 1..10 so cross-org queries work.
  // refresh_union_view is a SELECT against a plpgsql function; sqlu would
  // fail with "a result was returned when none was expected".
  def refreshUnionViews: DBIO[Unit] =
    DBIO.seq(
      sql"""SELECT pennsieve.refresh_union_view('datacanvases')""".as[Unit]
    )

}
