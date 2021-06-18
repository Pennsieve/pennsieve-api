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

import com.pennsieve.messages.{
  BackgroundJob,
  CachePopulationJob,
  CatalogDeleteJob,
  DatasetChangelogEventJob,
  DeleteDatasetJob,
  DeletePackageJob
}
import com.pennsieve.service.utilities.LogContext

package object contexts {

  def toContext(job: BackgroundJob): LogContext =
    job match {
      case DatasetChangelogEventJob(
          organizationId,
          datasetId,
          userId,
          traceId,
          _,
          _,
          _,
          _
          ) =>
        ChangelogEventContext(
          organizationId = organizationId,
          userId = userId,
          traceId = traceId,
          datasetId = datasetId
        )
      case DeleteDatasetJob(dsId, orgId, userId, traceId, _) =>
        DatasetDeleteContext(
          organizationId = orgId,
          userId = userId,
          datasetId = Some(dsId),
          traceId = traceId
        )

      case DeletePackageJob(packageId, orgId, userId, traceId, _) =>
        PackageDeleteContext(
          organizationId = orgId,
          userId = userId,
          packageId = Some(packageId),
          traceId = traceId
        )

      case c: CatalogDeleteJob =>
        CatalogDeleteContext(
          organizationId = c.organizationId,
          userId = c.userId
        )

      case CachePopulationJob(_, organizationId, _) =>
        StorageCacheContext(organizationId)
    }

}
