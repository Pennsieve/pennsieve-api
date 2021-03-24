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

package com.pennsieve.jobs

import java.util.UUID

import com.amazonaws.services.s3.model.MultiObjectDeleteException.DeleteError
import com.pennsieve.audit.middleware.TraceId
import com.pennsieve.streaming.LookupResultRow

sealed trait JobResult {
  val message: String
  val success: Boolean
}

sealed trait DeleteResult extends JobResult {
  def deletedResourceIds: Set[String]
  def getTraceId: Option[TraceId] = None

  def toMap: Map[String, Any] = Map("success" -> success, "message" -> message)
}

case class CachePopulationJobResult(
  success: Boolean = false,
  message: String = ""
) extends JobResult
case class InvalidJobResult(success: Boolean = false, message: String = "")
    extends JobResult

case class S3DeleteResult(
  bucket: String,
  deletedKeys: Seq[String],
  notDeletedKeys: Seq[DeleteError]
) extends DeleteResult {
  override val success: Boolean = notDeletedKeys.isEmpty
  override val message: String =
    if (success) s"Deleted ${deletedKeys.size} from $bucket"
    else {
      val notDeletedKeyIds = notDeletedKeys.map(_.getKey)
      s"Deleted ${deletedKeys.size} from $bucket but did not delete $notDeletedKeyIds"
    }

  override def deletedResourceIds: Set[String] = deletedKeys.toSet

  override def toMap: Map[String, Any] = super.toMap ++ Map(
    "bucket" -> bucket,
    "deletedKeys" -> deletedKeys,
    "notDeletedKeys" -> notDeletedKeys
  )
}

case class PackageDeleteResult(
  success: Boolean,
  message: String,
  packageNodeId: String,
  traceId: TraceId
) extends DeleteResult {
  override def getTraceId: Option[TraceId] = Some(traceId)
  override val deletedResourceIds: Set[String] = Set(packageNodeId)
}

case class GraphProxyDeleteResult(
  success: Boolean,
  message: String,
  proxyVertexId: UUID
) extends DeleteResult {
  override val deletedResourceIds: Set[String] = Set(proxyVertexId.toString)
}

case class DatasetDeleteResult(
  success: Boolean,
  message: String,
  datasetNodeId: Option[String],
  traceId: TraceId
) extends DeleteResult {
  override def getTraceId: Option[TraceId] = Some(traceId)
  override val deletedResourceIds: Set[String] = datasetNodeId match {
    case Some(nodeId) => Set(nodeId)
    case None => Set()
  }
}

case object NoOpDeleteResult extends DeleteResult {
  override val success = false
  override val message = "should never happen"
  override val deletedResourceIds = Set()
}

case class TabularDeleteResult(
  schema: String,
  tableName: String,
  error: Option[String] = None
) extends DeleteResult {

  override val success: Boolean = error.isEmpty

  override val message: String =
    if (success) s"Successfully dropped $schema.$tableName"
    else
      error
        .map(msg => s"Failed to drop $schema.$tableName: ${msg}")
        .getOrElse(s"Failed to drop $schema.$tableName")

  override def deletedResourceIds: Set[String] = Set(s"$schema.$tableName")

  override def toMap: Map[String, Any] = super.toMap ++ Map(
    "schema" -> schema,
    "tableName" -> tableName,
    "error" -> error
  )
}

case class InvalidChildDeleteResult(childNodeId: String) extends DeleteResult {
  override val success = false
  override val message = s"Failed to delete child from graph: $childNodeId"
  override val deletedResourceIds: Set[String] = Set(childNodeId)
}

case class RangeLookUpResult(
  channelId: String,
  lookupsToDelete: Seq[LookupResultRow],
  lookupsDeleted: Int
) extends DeleteResult {

  override val success: Boolean = lookupsToDelete.size == lookupsDeleted

  override val message: String =
    if (success)
      s"Succesfully deleted all $lookupsDeleted lookup for channel $channelId"
    else
      s"Failed to delete all lookups for $channelId. Only $lookupsDeleted/${lookupsToDelete.size} deleted"

  override def deletedResourceIds: Set[String] =
    lookupsToDelete.map(_.id.toString).toSet

  override def toMap: Map[String, Any] = super.toMap ++ Map(
    "channelId" -> channelId,
    "lookupsToDelete" -> lookupsToDelete,
    "lookupsDeleted" -> lookupsDeleted
  )
}

case class TimeSeriesSQLDeleteResult(
  tableName: String,
  rowsDeleted: Long,
  timeSeriesId: String
) extends DeleteResult {

  override val success: Boolean = true

  override val message: String =
    s"Successfully deleted $rowsDeleted rows from $tableName for time series package: $timeSeriesId"

  override def deletedResourceIds: Set[String] = Set.empty

  override def toMap: Map[String, Any] = super.toMap ++ Map(
    "timeSeriesId" -> timeSeriesId,
    "rowsDeleted" -> rowsDeleted,
    "tableName" -> tableName
  )
}

case class ThrowableResult(error: String) extends DeleteResult {

  override val success: Boolean = false

  override def deletedResourceIds: Set[String] = Set.empty

  override val message: String = error
}
