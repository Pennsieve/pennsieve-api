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

package com.pennsieve.managers

import java.time.ZonedDateTime

import cats.data._
import cats.implicits._
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.db.{ DatasetAssetsMapper, DatasetsMapper }
import com.pennsieve.traits.PostgresProfile.api._
import com.pennsieve.models.{ Dataset, DatasetAsset, DatasetState }
import com.rms.miu.slickcats.DBIOInstances._
import java.util.UUID

import com.pennsieve.domain.{ CoreError, ExceptionError, NotFound, SqlError }

import scala.concurrent.{ ExecutionContext, Future }

class DatasetAssetsManager(
  val db: Database,
  val datasetsMapper: DatasetsMapper
) {

  val organization = datasetsMapper.organization

  val datasetAssetsMapper: DatasetAssetsMapper = new DatasetAssetsMapper(
    organization
  )

  def createQuery(
    name: String,
    dataset: Dataset,
    bucket: String
  )(implicit
    ec: ExecutionContext
  ): DBIO[DatasetAsset] = {
    val id = UUID.randomUUID()
    val key = s"${organization.id}/${dataset.id}/$id/$name"

    val asset = DatasetAsset(
      name = name,
      s3Bucket = bucket,
      s3Key = key,
      datasetId = dataset.id,
      id = id
    )

    (datasetAssetsMapper += asset).map(_ => asset)
  }

  def getDatasetAssets(
    datasetId: Int
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, List[DatasetAsset]] =
    db.run(datasetAssetsMapper.filter(_.datasetId === datasetId).result)
      .map(_.toList)
      .toEitherT

  def deleteDatasetAsset(
    asset: DatasetAsset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] = {
    db.run(deleteQuery(asset)).toEitherT
  }

  def deleteQuery(
    asset: DatasetAsset
  )(implicit
    ec: ExecutionContext
  ): DBIO[Unit] = {
    datasetAssetsMapper.filter(_.id === asset.id).delete.map(_ => ())
  }

  def updateQuery(
    asset: DatasetAsset
  )(implicit
    ec: ExecutionContext
  ): DBIO[DatasetAsset] = {
    // Note: there is NOT an updatedAt trigger on the dataset_assets table
    val updatedAsset = asset.copy(updatedAt = ZonedDateTime.now())
    datasetAssetsMapper
      .filter(_.id === asset.id)
      .update(updatedAsset)
      .map(_ => updatedAsset)
  }

  /**
    * Update the banner image of a dataset.
    *
    * In order to do this transactionally, this method is passed an
    * `uploadAsset` function which is responsible for storing the contents of
    * the banner in S3.  This function is lifted into a DBIO so that a failure
    * during storage rolls back the creation of the `DatasetAsset` database row.
    *
    *
    * @param dataset the Dataset to update
    * @param bucket if the banner does not exist, store it in this bucket
    * @param filename the filename of the uploaded banner image
    * @param uploadNewAsset function that stores the BANNER in S3
    * @param deleteOldAsset function that deletes the previous BANNER from S3
    */
  def createOrUpdateBanner[T](
    dataset: Dataset,
    bucket: String,
    filename: String,
    uploadNewAsset: DatasetAsset => Either[Throwable, T],
    deleteOldAsset: DatasetAsset => Either[Throwable, T]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, T] = {
    val query = for {

      currentBanner <- dataset.bannerId.traverse { currentBannerId =>
        datasetAssetsMapper.getDatasetAsset(currentBannerId)
      }

      // Create the new dataset asset
      newBanner <- createQuery(filename, dataset, bucket)

      // Upload the banner
      uploadedAsset <- DBIO.from(
        uploadNewAsset(newBanner).fold(Future.failed, Future.successful)
      )

      // Save the banner asset on the dataset
      _ <- datasetsMapper
        .filter(_.id === dataset.id)
        .update(dataset.copy(bannerId = Some(newBanner.id)))

      // If one exists, delete the previous banner asset from S3
      _ <- currentBanner.traverse { previousBanner =>
        DBIO
          .from(
            deleteOldAsset(previousBanner)
              .fold(Future.failed, Future.successful)
              .map(_ => ())
          ): DBIO[Unit]
      }

      // If one exists, remove the previous banner asset from postgres
      _ <- currentBanner.traverse { previousBanner =>
        deleteQuery(previousBanner)
      }

    } yield uploadedAsset

    db.run(query.transactionally).toEitherT
  }

  def getBanner(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Option[DatasetAsset]] =
    db.run(
        datasetsMapper
          .filter(_.id === dataset.id)
          .join(datasetAssetsMapper)
          .on(_.bannerId === _.id)
          .map(_._2)
          .result
          .headOption
      )
      .toEitherT

  def getBannersByIds(
    bannerIds: List[UUID]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Seq[DatasetAsset]] =
    db.run(datasetAssetsMapper.getDatasetAssets(bannerIds.toSet)).toEitherT

  /**
    * Create or update the README of a dataset.
    * Create or update the README of a dataset.
    *
    * In order to do this transactionally, this method is passed an
    * `uploadAsset` function which is responsible for storing the contents of
    * the README in S3.  This function is lifted into a DBIO so that a failure
    * during storage rolls back the creation of the `DatasetAsset` database row.
    *
    * @param dataset the Dataset to update
    * @param bucket if the readme does not exist, store it in this bucket
    * @param filename if the readme does not exist, use this as its filename
    * @param uploadAsset function that stores the README in S3
    */
  def createOrUpdateReadme[T](
    dataset: Dataset,
    bucket: String,
    filename: String,
    uploadAsset: DatasetAsset => Either[Throwable, T]
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DatasetAsset] = {
    val query = for {

      // Get latest README
      datasetAndAsset <- datasetsMapper
        .get(dataset.id)
        .filter(_.state =!= (DatasetState.DELETING: DatasetState))
        .joinLeft(datasetAssetsMapper)
        .on(_.readmeId === _.id)
        .result
        .headOption
        .flatMap {
          case None =>
            DBIO.failed(SqlError(s"No dataset with id ${dataset.id} exists"))
          case Some(dataset) => DBIO.successful(dataset)
        }

      // Get or create the dataset asset
      readme <- datasetAndAsset match {
        case (_, Some(readme)) => updateQuery(readme)
        case (_, None) =>
          for {
            created <- createQuery(filename, dataset, bucket)
            _ <- datasetsMapper
              .filter(_.id === dataset.id)
              .update(dataset.copy(readmeId = Some(created.id)))
          } yield created
      }

      // Upload the readme
      _ <- DBIO.from(uploadAsset(readme).fold(Future.failed, Future.successful))

    } yield readme

    db.run(query.transactionally).toEitherT
  }

  def getReadme(
    dataset: Dataset
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Option[DatasetAsset]] =
    db.run(
        datasetsMapper
          .filter(_.id === dataset.id)
          .join(datasetAssetsMapper)
          .on(_.readmeId === _.id)
          .map(_._2)
          .result
          .headOption
      )
      .toEitherT

  /**
   * Create or update the Changelog of a dataset.
   *
   * In order to do this transactionally, this method is passed an
   * `uploadAsset` function which is responsible for storing the contents of
   * the Changrlog in S3.  This function is lifted into a DBIO so that a failure
   * during storage rolls back the creation of the `DatasetAsset` database row.
   *
   * @param dataset the Dataset to update
   * @param bucket if the changelog does not exist, store it in this bucket
   * @param filename if the changelog does not exist, use this as its filename
   * @param uploadAsset function that stores the changelog in S3
   */
  def createOrUpdateChangelog[T](
                               dataset: Dataset,
                               bucket: String,
                               filename: String,
                               uploadAsset: DatasetAsset => Either[Throwable, T]
                             )(implicit
                               ec: ExecutionContext
                             ): EitherT[Future, CoreError, DatasetAsset] = {
    val query = for {

      // Get latest changelog
      datasetAndAsset <- datasetsMapper
        .get(dataset.id)
        .filter(_.state =!= (DatasetState.DELETING: DatasetState))
        .joinLeft(datasetAssetsMapper)
        .on(_.changelogId === _.id)
        .result
        .headOption
        .flatMap {
          case None =>
            DBIO.failed(SqlError(s"No dataset with id ${dataset.id} exists"))
          case Some(dataset) => DBIO.successful(dataset)
        }

      // Get or create the dataset asset
      changelog <- datasetAndAsset match {
        case (_, Some(changelog)) => updateQuery(changelog)
        case (_, None) =>
          for {
            created <- createQuery(filename, dataset, bucket)
            _ <- datasetsMapper
              .filter(_.id === dataset.id)
              .update(dataset.copy(changelogId = Some(created.id)))
          } yield created
      }

      // Upload the changelog
      _ <- DBIO.from(uploadAsset(changelog).fold(Future.failed, Future.successful))

    } yield changelog

    db.run(query.transactionally).toEitherT
  }

  def getChangelog(
                 dataset: Dataset
               )(implicit
                 ec: ExecutionContext
               ): EitherT[Future, CoreError, Option[DatasetAsset]] =
    db.run(
      datasetsMapper
        .filter(_.id === dataset.id)
        .join(datasetAssetsMapper)
        .on(_.changelogId === _.id)
        .map(_._2)
        .result
        .headOption
    )
      .toEitherT
}
