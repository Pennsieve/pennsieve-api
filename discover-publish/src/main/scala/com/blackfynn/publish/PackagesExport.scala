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

package com.pennsieve.publish

import akka.actor.ActorSystem
import akka.stream.{ ActorAttributes, Supervision }
import akka.stream.scaladsl.{ Keep }
import akka.stream.scaladsl.Sink
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import com.pennsieve.models.{ ExternalId, FileManifest }
import com.pennsieve.publish.models.{ CopyAction, PackageExternalIdMap }
import scala.concurrent.{ ExecutionContext, Future }

object PackagesExport extends LazyLogging {

  val supervision: Supervision.Decider = {
    case e => {
      logger.error("Stream error", e)
      Supervision.Stop
    }
  }

  def exportPackageSources(
    container: PublishContainer
  )(implicit
    ec: ExecutionContext,
    system: ActorSystem
  ): Future[(PackageExternalIdMap, List[FileManifest])] = {
    implicit val publishContainer: PublishContainer = container

    val (manifestF, packageNodeIdF) = PackagesSource()
      .withAttributes(ActorAttributes.supervisionStrategy(supervision))
      .via(BuildCopyRequests())
      .via(CopyS3ObjectsFlow())
      .alsoToMat(buildFileManifest)(Keep.right)
      .toMat(buildPackageExternalIdMap)(Keep.both)
      .run()

    for {
      manifest <- manifestF
      nodeIdMap <- packageNodeIdF
    } yield (nodeIdMap, manifest)
  }

  def buildFileManifest(
    implicit
    container: PublishContainer
  ): Sink[CopyAction, Future[List[FileManifest]]] =
    Sink.fold(Nil: List[FileManifest])(
      (accum, action: CopyAction) =>
        FileManifest(
          path = action.fileKey,
          size = action.file.size,
          fileType = action.file.fileType,
          sourcePackageId = Some(action.pkg.nodeId),
          id = Some(action.file.uuid)
        ) :: accum
    )

  /**
    * Map package ID to S3 path so that `model-publish` can rewrite node IDs as
    * package paths.
    *
    * This sink receives multiple CopyActions (one per source file) for each
    * package.  However, since all source files belonging to a package have the
    * same `packageKey` they are correctly de-duplicated.
    */
  def buildPackageExternalIdMap(
    implicit
    container: PublishContainer
  ): Sink[CopyAction, Future[PackageExternalIdMap]] =
    Sink.fold(Map.empty: PackageExternalIdMap)(
      (accum, action: CopyAction) =>
        accum ++ Map(
          ExternalId.nodeId(action.pkg.nodeId) -> action.packageKey,
          ExternalId.intId(action.pkg.id) -> action.packageKey
        )
    )
}
