package com.pennsieve.publish.models

import com.pennsieve.models.{ File, Package }
import com.pennsieve.publish.utils.joinKeys

/**
  * Container that describes the source and destination of an S3 file to copy.
  *
  * The `packageKey` is the "natural" path to the package that the file
  * belongs to.  If the package has a single source file, this is the source
  * file itself; otherwise, this is the directory that contains the source
  * file and its siblings.
  */
case class CopyAction(
  pkg: Package,
  file: File,
  toBucket: String,
  baseKey: String,
  fileKey: String,
  packageKey: String
) {
  def copyToKey: String = joinKeys(baseKey, fileKey)
}
