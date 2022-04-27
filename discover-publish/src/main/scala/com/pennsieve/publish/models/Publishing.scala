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

package com.pennsieve.publish.models

import com.pennsieve.models.{ ExternalId, FileManifest }
import io.circe._
import io.circe.syntax._
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

case class FileManifestIDWrapper(manifest: FileManifest)

object FileManifestIDWrapper {
  implicit val encoder: Encoder[FileManifestIDWrapper] =
    new Encoder[FileManifestIDWrapper] {
      final def apply(manifest: FileManifestIDWrapper): Json = {
        manifest.manifest.asJson
          .mapObject(_.add("id", manifest.manifest.id.asJson))
      }
    }

  implicit val decoder: Decoder[FileManifestIDWrapper] =
    new Decoder[FileManifestIDWrapper] {
      final def apply(c: HCursor): Decoder.Result[FileManifestIDWrapper] =
        for {
          manifest <- c.as[FileManifest]
        } yield FileManifestIDWrapper(manifest = manifest)
    }
}

// The result of publishing the assets of a dataset.
case class PublishAssetResult(
  externalIdToPackagePath: PackageExternalIdMap,
  packageManifests: List[FileManifestIDWrapper],
  bannerKey: String,
  bannerManifest: FileManifestIDWrapper,
  readmeKey: String,
  readmeManifest: FileManifestIDWrapper,
  changelogKey: String,
  changelogManifest: FileManifestIDWrapper
)

object PublishAssetResult {

  implicit val encoder: Encoder[PublishAssetResult] =
    deriveEncoder[PublishAssetResult]
  implicit val decoder: Decoder[PublishAssetResult] =
    deriveDecoder[PublishAssetResult]

  def apply(
    externalIdToPackagePath: PackageExternalIdMap,
    packageManifests: List[FileManifest],
    bannerKey: String,
    bannerManifest: FileManifest,
    readmeKey: String,
    readmeManifest: FileManifest,
    changelogKey: String,
    changelogManifest: FileManifest
  ): PublishAssetResult =
    new PublishAssetResult(
      externalIdToPackagePath = externalIdToPackagePath,
      packageManifests = packageManifests.map(FileManifestIDWrapper(_)),
      bannerKey = bannerKey,
      bannerManifest = FileManifestIDWrapper(bannerManifest),
      readmeKey = readmeKey,
      readmeManifest = FileManifestIDWrapper(readmeManifest),
      changelogKey = changelogKey,
      changelogManifest = FileManifestIDWrapper(changelogManifest)
    )
}

case class ExportedGraphResult(manifests: List[FileManifest])

object ExportedGraphResult {
  implicit val encoder: Encoder[ExportedGraphResult] =
    deriveEncoder[ExportedGraphResult]
  implicit val decoder: Decoder[ExportedGraphResult] =
    deriveDecoder[ExportedGraphResult]
}
