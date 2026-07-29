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
import com.pennsieve.models.DefaultDatasetStatus

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

  // Rendered SQL list literal, e.g. 'NO_STATUS','WORK_IN_PROGRESS',... —
  // values are the EnumEntry names (uppercase + snake case).
  private def defaultDatasetStatusNamesSql: String =
    DefaultDatasetStatus.values.map(s => s"'${s.entryName}'").mkString(",")

  // Used to clear the tables in the test postgres database.
  //
  // Also clears the pre-seeded organization schemas (1..10) baked into
  // the pennsievedb-seed image. Tests manage their own fixtures, so any seed
  // rows must be cleared before each test regardless of which harness is
  // running. Keep this list in sync with ORGANIZATION_SCHEMA_COUNT in
  // pennsieve-db-migrations/scripts/build-postgres.sh.
  val seededOrganizationIds: Seq[Int] = 1 to 10

  // Root tables whose FK closure covers everything tests may write. These are
  // the same roots deepClearDB TRUNCATEs; fastClean expands them with the same
  // CASCADE semantics (transitive closure of referencing tables).
  private val pennsieveRootTables: Seq[String] = Seq(
    "users",
    "organizations",
    "teams",
    "organization_team",
    "organization_user",
    "team_user",
    "user_invite",
    "tokens"
  )

  private val organizationRootTables: Seq[String] = Seq(
    "datasets",
    "packages",
    "annotations",
    "annotation_layers",
    "contributors",
    "dataset_contributor",
    "collections",
    "data_use_agreements",
    "datacanvases"
  )

  /**
    * Fast test-database cleanup: one server-side DO block instead of ~100
    * autocommitted TRUNCATE round trips (which cost ~2.5s per test).
    *
    * State-identical to TRUNCATE ... RESTART IDENTITY CASCADE over the same
    * root tables:
    *   - the recursive CTE computes exactly the closure TRUNCATE ... CASCADE
    *     truncates (transitive FK-referencing tables),
    *   - every closure table is emptied (FK triggers are disabled for this
    *     transaction via session_replication_role, so deletion order does not
    *     matter — every referencing table is itself in the closure),
    *   - every sequence owned by a closure table column is restarted, which
    *     is precisely RESTART IDENTITY. Tests rely on this: organization ids
    *     restart at 1 and map onto the seeded schemas "1"../"10".
    */
  private def fastCleanSql(
    roots: Seq[(String, Seq[String])],
    preserveDefaultDatasetStatusSchemas: Seq[String]
  ): String = {
    val rootPredicates = roots
      .map {
        case (schema, tables) =>
          val tableList = tables.map(t => s"'$t'").mkString(", ")
          s"(n.nspname = '$schema' AND c.relname IN ($tableList))"
      }
      .mkString("\n             OR ")

    // Preserve the seed image's 4 default dataset_status rows (NO_STATUS,
    // WORK_IN_PROGRESS, IN_REVIEW, COMPLETED) so DatasetManager.getDefaultStatus
    // works in harnesses that don't call resetDefaultStatusOptions (admin,
    // authorization-service). Delete only test-added rows; datasets and
    // datacanvases (which FK into dataset_status) are already emptied above.
    val datasetStatusDeletes = preserveDefaultDatasetStatusSchemas
      .map(
        schema =>
          s"""DELETE FROM "$schema"."dataset_status" WHERE name NOT IN ($defaultDatasetStatusNamesSql);"""
      )
      .mkString("\n  ")

    s"""DO $$pgclean$$
DECLARE
  targets oid[];
  rel oid;
BEGIN
  PERFORM set_config('session_replication_role', 'replica', true);

  WITH RECURSIVE closure(oid) AS (
    SELECT c.oid
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE c.relkind IN ('r', 'p')
       AND ($rootPredicates)
    UNION
    SELECT con.conrelid
      FROM closure cl
      JOIN pg_constraint con
        ON con.confrelid = cl.oid AND con.contype = 'f'
  )
  SELECT array_agg(oid) INTO targets FROM closure;

  IF targets IS NOT NULL THEN
    FOREACH rel IN ARRAY targets LOOP
      EXECUTE format('DELETE FROM %s', rel::regclass);
    END LOOP;

    -- RESTART IDENTITY: reset every sequence owned by a closure table column
    -- back to its start value (setval with is_called = false is what
    -- ALTER SEQUENCE ... RESTART does, without per-sequence DDL locks).
    PERFORM setval(s.seqrelid, s.seqstart, false)
       FROM pg_sequence s
       JOIN pg_depend d ON d.objid = s.seqrelid AND d.deptype IN ('a', 'i')
      WHERE d.refobjid = ANY (targets);
  END IF;

  $datasetStatusDeletes
END
$$pgclean$$"""
  }

  private def fastClean(
    roots: Seq[(String, Seq[String])],
    preserveDefaultDatasetStatusSchemas: Seq[String]
  ): DBIO[Unit] =
    DBIO.seq(sqlu"#${fastCleanSql(roots, preserveDefaultDatasetStatusSchemas)}")

  def clearDB: DBIO[Unit] = {
    val schemas = seededOrganizationIds.map(_.toString)
    fastClean(
      ("pennsieve" -> pennsieveRootTables) +: schemas.map(
        _ -> organizationRootTables
      ),
      schemas
    )
  }

  // TRUNCATE-based variant of clearDB. Run once per suite (not per test): it
  // is much slower than clearDB but rewrites relfilenodes, which compacts any
  // table bloat left behind by the DELETE-based fast path.
  def deepClearDB: DBIO[Unit] = DBIO.seq(
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
    ) ++ seededOrganizationIds.map(truncateOrganizationSchema): _*
  )

  // Used to clear the tables in the test postgres database in an organization's schema
  def clearOrganizationSchema(organizationId: Int): DBIO[Unit] = {
    val schema: String = organizationId.toString
    fastClean(Seq(schema -> organizationRootTables), Seq(schema))
  }

  def clearOrganizationSchemas(organizationIds: Seq[Int]): DBIO[Unit] =
    if (organizationIds.isEmpty) DBIO.successful(())
    else {
      val schemas = organizationIds.map(_.toString)
      fastClean(schemas.map(_ -> organizationRootTables), schemas)
    }

  // TRUNCATE-based variant of clearOrganizationSchema; see deepClearDB.
  def truncateOrganizationSchema(organizationId: Int): DBIO[Unit] = {
    val schema: String = organizationId.toString

    // Preserve the seed image's 4 default dataset_status rows (NO_STATUS,
    // WORK_IN_PROGRESS, IN_REVIEW, COMPLETED) so DatasetManager.getDefaultStatus
    // works in harnesses that don't call resetDefaultStatusOptions (admin,
    // authorization-service). Delete only test-added rows. datasets and
    // datacanvases must be truncated first — both FK into dataset_status, and
    // the DELETE would otherwise hit ON DELETE RESTRICT.
    DBIO.seq(
      sqlu"""TRUNCATE TABLE "#$schema"."datasets" RESTART IDENTITY CASCADE""",
      sqlu"""TRUNCATE TABLE "#$schema"."packages" RESTART IDENTITY CASCADE""",
      sqlu"""TRUNCATE TABLE "#$schema"."annotations" RESTART IDENTITY CASCADE""",
      sqlu"""TRUNCATE TABLE "#$schema"."annotation_layers" RESTART IDENTITY CASCADE""",
      sqlu"""TRUNCATE TABLE "#$schema"."contributors" RESTART IDENTITY CASCADE""",
      sqlu"""TRUNCATE TABLE "#$schema"."dataset_contributor" RESTART IDENTITY CASCADE""",
      sqlu"""TRUNCATE TABLE "#$schema"."collections" RESTART IDENTITY CASCADE""",
      sqlu"""TRUNCATE TABLE "#$schema"."data_use_agreements" RESTART IDENTITY CASCADE""",
      sqlu"""TRUNCATE TABLE "#$schema"."datacanvases" RESTART IDENTITY CASCADE""",
      sqlu"""DELETE FROM "#$schema"."dataset_status" WHERE name NOT IN (#$defaultDatasetStatusNamesSql)"""
    )
  }

  def clearDBSchema: DBIO[Unit] =
    fastClean(
      Seq(
        "timeseries" -> Seq("ranges", "annotations", "channel_groups", "layers")
      ),
      Seq.empty
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
