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
import com.pennsieve.db.ExternalFilesMapper
import com.pennsieve.domain.{ CoreError, NotFound, UnsupportedPackageType }
import com.pennsieve.managers.{ ExternalFileManager, PackageManager }
import com.pennsieve.models.{ ExternalFile, Organization, Package, PackageType }

import scala.concurrent.{ ExecutionContext, Future }

/**
  * `ExternalFileManager` is a plain class with `val db = packageManager.db`,
  * which would eagerly trip the throwing-db on construction. This subclass
  * doesn't reference packageManager.db; it stores ExternalFile rows in a
  * private map keyed by (orgId, packageId).
  */
class FakeExternalFileManager(
  state: InMemoryState,
  org: Organization,
  externalFiles: ExternalFilesMapper,
  packageManager: PackageManager
) extends ExternalFileManager(externalFiles, packageManager) {

  // We don't reference db in any overridden method; the lazy val on the
  // superclass means db is never resolved. Storage lives in InMemoryState so
  // it's shared across the SecureContainer instances the controller and the
  // test fixture build.

  override def create(
    `package`: Package,
    location: String,
    description: Option[String] = None
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, ExternalFile] =
    if (`package`.`type` != PackageType.ExternalFile)
      EitherT.leftT(UnsupportedPackageType(`package`.`type`))
    else {
      val ef = ExternalFile(
        packageId = `package`.id,
        location = location,
        description = description
      )
      state.externalFiles.put((org.id, `package`.id), ef)
      EitherT.rightT(ef)
    }

  override def get(
    `package`: Package
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, ExternalFile] =
    if (`package`.`type` != PackageType.ExternalFile)
      EitherT.leftT(UnsupportedPackageType(`package`.`type`))
    else
      state.externalFiles.get((org.id, `package`.id)) match {
        case Some(ef) => EitherT.rightT(ef)
        case None =>
          EitherT.leftT(
            NotFound(s"External file for (${`package`.nodeId})"): CoreError
          )
      }

  override def getMap(
    packages: Seq[Package]
  )(implicit
    ec: ExecutionContext
  ): Future[Map[Int, ExternalFile]] = {
    val pids = packages.map(_.id).toSet
    Future.successful(state.externalFiles.collect {
      case ((o, pid), ef) if o == org.id && pids.contains(pid) =>
        pid -> ef
    }.toMap)
  }
}
