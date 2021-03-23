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

      case CachePopulationJob(_, organizationId, _, _) =>
        StorageCacheContext(organizationId)
    }

}
