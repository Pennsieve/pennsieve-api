package com.blackfynn.dtos

import java.net.URL

case class DownloadManifestEntry(
  nodeId: String,
  fileName: String,
  packageName: String,
  path: List[String] = List.empty,
  url: URL,
  size: Long,
  fileExtension: Option[String] = None
)

case class DownloadManifestHeader(count: Int, size: Long)

case class DownloadRequest(
  nodeIds: List[String],
  fileIds: Option[List[Int]] = None
)
case class DownloadManifestDTO(
  header: DownloadManifestHeader,
  data: List[DownloadManifestEntry]
)
