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

import com.pennsieve.db.FilesTable.{ OrderByColumn, OrderByDirection }
import com.pennsieve.managers.{ FileManager, PackageManager }
import com.pennsieve.models.{
  File,
  FileObjectType => FileObjType,
  Organization,
  Package
}

import scala.concurrent.{ ExecutionContext, Future }

/**
  * In-memory fake of `FileManager`. Reads `state.files`.
  */
class FakeFileManager(
  val packageManager: PackageManager,
  override val organization: Organization
) extends FileManager {

  private def state: InMemoryState =
    packageManager
      .asInstanceOf[FakePackageManager]
      .datasetManager
      .asInstanceOf[FakeDatasetManager]
      .state

  private def filesForPackage(packageId: Int): Iterable[File] =
    state.files.collect {
      case ((orgId, _), f)
          if orgId == organization.id && f.packageId == packageId =>
        f
    }

  override def getSingleSourceMap(
    `packages`: Seq[Package],
    limit: Option[Int] = None,
    offset: Option[Int] = None,
    orderBy: Option[(OrderByColumn, OrderByDirection)] = None,
    excludePending: Boolean = false
  )(implicit
    ec: ExecutionContext
  ): Future[Map[Int, Option[File]]] =
    Future.successful(`packages`.map { p =>
      val sources = filesForPackage(p.id)
        .filter(_.objectType == FileObjType.Source)
        .filterNot(
          f =>
            excludePending && f.uploadedState
              .contains(com.pennsieve.models.FileState.PENDING)
        )
        .toSeq
      val single = if (sources.size == 1) sources.headOption else None
      p.id -> single
    }.toMap)
}
