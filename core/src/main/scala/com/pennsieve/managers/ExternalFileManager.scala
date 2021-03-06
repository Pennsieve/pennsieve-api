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

import cats.implicits._
import cats.data.EitherT
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.db.{ ExternalFilesMapper, PackagesMapper }
import com.pennsieve.domain.{
  CoreError,
  NotFound,
  SqlError,
  UnsupportedPackageType
}
import com.pennsieve.models.{ ExternalFile, Package, PackageType }
import com.pennsieve.traits.PostgresProfile.api._

import scala.concurrent.{ ExecutionContext, Future }

/**
  * External files are similar to files in that they are represented as packages in the platform, however we do not
  * actually store, manage, or process the file contents.
  *
  * @param externalFiles
  * @param packageManager
  */
class ExternalFileManager(
  externalFiles: ExternalFilesMapper,
  packageManager: PackageManager
) {
  val db = packageManager.db

  val mapper = externalFiles

  def create(
    `package`: Package,
    location: String,
    description: Option[String] = None
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, ExternalFile] = {
    if (`package`.`type` != PackageType.ExternalFile) {
      return EitherT.leftT(UnsupportedPackageType(`package`.`type`))
    }
    db.run(
        externalFiles returning externalFiles += ExternalFile(
          packageId = `package`.id,
          location = location,
          description = description
        )
      )
      .toEitherT
  }

  def get(
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, ExternalFile] = {
    if (`package`.`type` != PackageType.ExternalFile) {
      return EitherT.leftT(UnsupportedPackageType(`package`.`type`))
    }
    db.run(externalFiles.get(`package`).result.headOption)
      .whenNone(NotFound(s"External file for (${`package`.nodeId})"): CoreError)
  }

  def getMap(
    packages: Seq[Package]
  )(implicit
    ec: ExecutionContext
  ): Future[Map[Int, ExternalFile]] =
    db.run(externalFiles.get(packages).result)
      .map(_.map(externalFile => externalFile.packageId -> externalFile).toMap)

  private def doUpdate(
    `package`: Package,
    externalFile: ExternalFile
  )(implicit
    ec: ExecutionContext
  ): DBIO[ExternalFile] =
    externalFiles
      .get(`package`)
      .update(externalFile)
      .flatMap {
        case 1 => DBIO.successful(externalFile)
        case _ =>
          DBIO.failed(
            SqlError(
              s"Failed to update external file for package ${`package`.nodeId}"
            )
          )
      }

  def update(
    `package`: Package,
    externalFile: ExternalFile
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, ExternalFile] = {
    if (`package`.`type` != PackageType.ExternalFile) {
      return EitherT.leftT(UnsupportedPackageType(`package`.`type`))
    }
    db.run(doUpdate(`package`, externalFile)).toEitherT
  }

  def selectForUpdate(
    `package`: Package,
    location: String,
    description: Option[String] = None
  )(implicit
    ec: ExecutionContext
  ): DBIO[ExternalFile] = {
    if (`package`.`type` != PackageType.ExternalFile) {
      return DBIO.failed(
        SqlError(UnsupportedPackageType(`package`.`type`).getMessage)
      )
    }
    (for {
      externalFile <- externalFiles
        .get(`package`)
        .result
        .headOption
        .flatMap {
          case Some(file) => DBIO.successful(file)
          case None =>
            DBIO.failed(
              SqlError(
                s"No external file found for package ${`package`.nodeId}"
              )
            )
        }
      updatedFile <- doUpdate(
        `package`,
        externalFile.copy(location = location, description = description)
      )
    } yield updatedFile).transactionally
  }

  def delete(
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Unit] = {
    if (`package`.`type` != PackageType.ExternalFile) {
      return EitherT.leftT(UnsupportedPackageType(`package`.`type`))
    }
    db.run(
        externalFiles
          .get(`package`)
          .delete
          .flatMap {
            case 1 => DBIO.successful(())
            case _ =>
              DBIO.failed(
                SqlError(s"No external file found for package ${`package`.id}")
              )
          }
      )
      .toEitherT
  }
}
