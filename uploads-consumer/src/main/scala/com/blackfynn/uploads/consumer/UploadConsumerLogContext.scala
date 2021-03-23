package com.pennsieve.uploads.consumer

import com.pennsieve.models.Manifest
import com.pennsieve.service.utilities.LogContext

final case class UploadConsumerLogContext(
  importId: String,
  organizationId: Int,
  userId: Int,
  packageId: Int,
  datasetId: Int
) extends LogContext {
  override val values: Map[String, String] = inferValues(this)
}

object UploadConsumerLogContext {
  def apply(manifest: Manifest): UploadConsumerLogContext =
    UploadConsumerLogContext(
      manifest.importId.toString,
      manifest.organizationId,
      manifest.content.userId,
      manifest.content.packageId,
      manifest.content.datasetId
    )
}
