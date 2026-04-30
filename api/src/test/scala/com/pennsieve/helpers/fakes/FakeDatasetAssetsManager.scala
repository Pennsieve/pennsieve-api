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

package com.pennsieve.helpers.fakes

import cats.data.EitherT
import com.pennsieve.db.DatasetsMapper
import com.pennsieve.domain.CoreError
import com.pennsieve.managers.DatasetAssetsManager
import com.pennsieve.models.{ Dataset, DatasetAsset, Organization }
import com.pennsieve.traits.PostgresProfile.api.Database

import java.util.UUID
import scala.concurrent.{ ExecutionContext, Future }

/** In-memory fake of `DatasetAssetsManager`. Reads/writes `state.datasetAssets`. */
class FakeDatasetAssetsManager(val state: InMemoryState, org: Organization)
    extends DatasetAssetsManager {

  def db: Database =
    sys.error(
      "FakeDatasetAssetsManager: a method not yet stubbed by your test " +
        "tried to use the database. Override the method on this fake."
    )

  override lazy val datasetsMapper: DatasetsMapper =
    new DatasetsMapper(org)

  // Asset rows live in `state.datasetAssetsByUuid` (UUID-keyed) so they are
  // shared across the multiple `SecureContainer` instances built per request
  // by the cake.

  override def createQuery(
    name: String,
    dataset: Dataset,
    bucket: String
  )(implicit
    ec: ExecutionContext
  ): com.pennsieve.traits.PostgresProfile.api.DBIO[DatasetAsset] = {
    val id = UUID.randomUUID()
    val key = s"${org.id}/${dataset.id}/$id/$name"
    val asset = DatasetAsset(
      name = name,
      s3Bucket = bucket,
      s3Key = key,
      datasetId = dataset.id,
      id = id
    )
    state.datasetAssetsByUuid.put((org.id, id), asset)
    com.pennsieve.traits.PostgresProfile.api.DBIO.successful(asset)
  }

  override def getBanner(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Option[DatasetAsset]] = {
    val currentBannerId = state.datasets
      .get((org.id, dataset.id))
      .flatMap(_.bannerId)
    EitherT.rightT(
      currentBannerId.flatMap(id => state.datasetAssetsByUuid.get((org.id, id)))
    )
  }

  override def getBannersByIds(
    bannerIds: List[UUID]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[DatasetAsset]] = {
    val ids = bannerIds.toSet
    val out = state.datasetAssetsByUuid.collect {
      case ((orgId, id), a) if orgId == org.id && ids.contains(id) => a
    }.toSeq
    EitherT.rightT(out)
  }

  override def getReadme(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Option[DatasetAsset]] = {
    // Mirror the core impl which joins on the *current* readmeId from the
    // datasets table — not the stale `readmeId` on the parameter.
    val currentReadmeId = state.datasets
      .get((org.id, dataset.id))
      .flatMap(_.readmeId)
    EitherT.rightT(
      currentReadmeId.flatMap(id => state.datasetAssetsByUuid.get((org.id, id)))
    )
  }

  override def getChangelog(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Option[DatasetAsset]] = {
    val currentChangelogId = state.datasets
      .get((org.id, dataset.id))
      .flatMap(_.changelogId)
    EitherT.rightT(
      currentChangelogId
        .flatMap(id => state.datasetAssetsByUuid.get((org.id, id)))
    )
  }

  override def createOrUpdateReadme[T](
    dataset: Dataset,
    bucket: String,
    filename: String,
    uploadAsset: DatasetAsset => Either[Throwable, T]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DatasetAsset] = {
    val existing =
      dataset.readmeId.flatMap(
        id => state.datasetAssetsByUuid.get((org.id, id))
      )
    val asset = existing.getOrElse {
      val newId = UUID.randomUUID()
      val a = DatasetAsset(
        name = filename,
        s3Bucket = bucket,
        s3Key = s"${org.id}/${dataset.id}/$newId/$filename",
        datasetId = dataset.id,
        id = newId
      )
      state.datasetAssetsByUuid.put((org.id, newId), a)
      // Bump the asset's etag on update (etag = epoch-millis-of-updatedAt)
      state.datasets
        .put((org.id, dataset.id), dataset.copy(readmeId = Some(newId)))
      a
    }
    // Refresh asset etag/updatedAt on every call so subsequent reads see a
    // new ETag (mirroring the Postgres dataset_asset_update_etag trigger).
    val newUpdatedAt = {
      val now = InMemoryState.now()
      if (now.toEpochSecond > asset.updatedAt.toEpochSecond) now
      else asset.updatedAt.plusSeconds(1)
    }
    val refreshed = asset.copy(updatedAt = newUpdatedAt)
    state.datasetAssetsByUuid.put((org.id, refreshed.id), refreshed)
    uploadAsset(refreshed) match {
      case Right(_) => EitherT.rightT(refreshed)
      case Left(err) =>
        EitherT.leftT(
          com.pennsieve.domain
            .ExceptionError(new RuntimeException(err.getMessage))
        )
    }
  }

  override def createOrUpdateBanner[T](
    dataset: Dataset,
    bucket: String,
    filename: String,
    uploadNewAsset: DatasetAsset => Either[Throwable, T],
    deleteOldAsset: DatasetAsset => Either[Throwable, T]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, T] = {
    val existing =
      dataset.bannerId.flatMap(
        id => state.datasetAssetsByUuid.get((org.id, id))
      )
    existing.foreach(deleteOldAsset(_))
    val newId = UUID.randomUUID()
    val a = DatasetAsset(
      name = filename,
      s3Bucket = bucket,
      s3Key = s"${org.id}/${dataset.id}/$newId/$filename",
      datasetId = dataset.id,
      id = newId
    )
    state.datasetAssetsByUuid.put((org.id, newId), a)
    state.datasets
      .put((org.id, dataset.id), dataset.copy(bannerId = Some(newId)))
    uploadNewAsset(a) match {
      case Right(t) => EitherT.rightT(t)
      case Left(err) =>
        EitherT.leftT(
          com.pennsieve.domain
            .ExceptionError(new RuntimeException(err.getMessage))
        )
    }
  }

  /**
    * Mints a new DatasetAsset and stores it in `state.datasetAssetsByUuid`. The dataset's
    * changelogId is updated via `state.datasets`. Then the upload callback
    * runs (mock S3 client) — but the asset itself is the return value.
    */
  override def createOrUpdateChangelog[T](
    dataset: Dataset,
    bucket: String,
    filename: String,
    uploadAsset: DatasetAsset => Either[Throwable, T]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DatasetAsset] = {
    val existingChangelog =
      dataset.changelogId.flatMap(
        id => state.datasetAssetsByUuid.get((org.id, id))
      )
    val asset = existingChangelog.getOrElse {
      val newId = UUID.randomUUID()
      val a = DatasetAsset(
        name = filename,
        s3Bucket = bucket,
        s3Key = s"${org.id}/${dataset.id}/$newId/$filename",
        datasetId = dataset.id,
        id = newId
      )
      state.datasetAssetsByUuid.put((org.id, newId), a)
      // link dataset.changelogId
      state.datasets
        .put((org.id, dataset.id), dataset.copy(changelogId = Some(newId)))
      a
    }
    uploadAsset(asset) match {
      case Right(_) => EitherT.rightT(asset)
      case Left(err) =>
        EitherT.leftT(
          com.pennsieve.domain
            .ExceptionError(new RuntimeException(err.getMessage))
        )
    }
  }

  override def getOrCreateChangelog[T](
    dataset: Dataset,
    bucket: String,
    filename: String,
    uploadAsset: DatasetAsset => Either[Throwable, T]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DatasetAsset] = {
    dataset.changelogId.flatMap(
      id => state.datasetAssetsByUuid.get((org.id, id))
    ) match {
      case Some(a) => EitherT.rightT(a)
      case None =>
        createOrUpdateChangelog(dataset, bucket, filename, uploadAsset)
    }
  }
}
