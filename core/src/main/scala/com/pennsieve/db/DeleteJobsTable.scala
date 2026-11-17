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

import com.pennsieve.messages.DeletePackageJob
import com.pennsieve.models.{ Organization, Package }
import com.pennsieve.traits.PostgresProfile.api._
import slick.jdbc.GetResult

/**
  * Values of delete_jobs.source, fixed by the CHECK constraint in
  * pennsieve-db-migrations V20261117092140__create_delete_jobs.sql.
  * pennsieve-api only produces API_DELETE.
  */
object DeleteJobSource {
  val ApiDelete: String = "API_DELETE"
}

/** A delete_jobs row, as far as the producer cares. */
final case class DeleteJobRecord(
  id: String,
  packageId: Int,
  packageNodeId: String,
  datasetId: Int,
  userNodeId: Option[String],
  traceId: Option[String],
  source: String,
  state: String,
  attempts: Int
)

/**
  * The organization's delete_jobs table: the durable record of each
  * package-delete job (pennsieve-db-migrations
  * V20261117092140__create_delete_jobs.sql).
  *
  * Contract (process-jobs-service#29): the producer inserts the row as
  * PENDING in the same transaction that sets the package to DELETING, then
  * enqueues a DeletePackageJob whose id is the row's id. The consumer only
  * moves the row forward. Only the requested top-level package gets a row;
  * its descendants are handled by that one job.
  */
class DeleteJobsMapper(val organization: Organization) {

  private val schema: String = organization.schemaId

  /**
    * Insert the PENDING row for `job`. Run it inside the transaction that
    * sets `pkg` to DELETING.
    */
  def insertPending(
    job: DeletePackageJob,
    pkg: Package,
    source: String = DeleteJobSource.ApiDelete
  ): DBIO[Int] =
    sqlu"""
      INSERT INTO "#$schema".delete_jobs
        (id, package_id, package_node_id, dataset_id, user_node_id,
         trace_id, source, state, attempts)
      VALUES
        (CAST(${job.id} AS uuid), ${pkg.id}, ${pkg.nodeId}, ${pkg.datasetId},
         ${job.userId}, ${job.traceId.id}, $source, 'PENDING', 0)
    """

  private implicit val getDeleteJobRecord: GetResult[DeleteJobRecord] =
    GetResult(
      r =>
        DeleteJobRecord(
          id = r.<<,
          packageId = r.<<,
          packageNodeId = r.<<,
          datasetId = r.<<,
          userNodeId = r.<<?,
          traceId = r.<<?,
          source = r.<<,
          state = r.<<,
          attempts = r.<<
        )
    )

  private val selectColumns: String =
    "id::text, package_id, package_node_id, dataset_id, user_node_id, " +
      "trace_id, source, state, attempts"

  def get(id: String): DBIO[Option[DeleteJobRecord]] =
    sql"""
      SELECT #$selectColumns
      FROM "#$schema".delete_jobs
      WHERE id = CAST($id AS uuid)
    """.as[DeleteJobRecord].headOption

  def getByPackageNodeId(nodeId: String): DBIO[Vector[DeleteJobRecord]] =
    sql"""
      SELECT #$selectColumns
      FROM "#$schema".delete_jobs
      WHERE package_node_id = $nodeId
    """.as[DeleteJobRecord]
}
