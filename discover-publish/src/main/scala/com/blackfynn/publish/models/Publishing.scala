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
  readmeManifest: FileManifestIDWrapper
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
    readmeManifest: FileManifest
  ): PublishAssetResult =
    new PublishAssetResult(
      externalIdToPackagePath = externalIdToPackagePath,
      packageManifests = packageManifests.map(FileManifestIDWrapper(_)),
      bannerKey = bannerKey,
      bannerManifest = FileManifestIDWrapper(bannerManifest),
      readmeKey = readmeKey,
      readmeManifest = FileManifestIDWrapper(readmeManifest)
    )
}

case class ExportedGraphResult(manifests: List[FileManifest])

object ExportedGraphResult {
  implicit val encoder: Encoder[ExportedGraphResult] =
    deriveEncoder[ExportedGraphResult]
  implicit val decoder: Decoder[ExportedGraphResult] =
    deriveDecoder[ExportedGraphResult]
}
