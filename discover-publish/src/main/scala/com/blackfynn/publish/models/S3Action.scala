package com.pennsieve.publish.models

import com.pennsieve.models.{ File, Package }
import com.pennsieve.publish.utils.joinKeys

/**
  * Container that describes an s3 action for a file.
  * Copy contains the information necessary to move a file
  * NoOp exists so we can write the full Manifest without moving the file
  */
sealed trait S3Action {
  val pkg: Package
  val file: File
  val baseKey: String
  val fileKey: String
  val packageKey: String
  val s3version: Option[String]
}

/**
  * The `packageKey` is the "natural" path to the package that the file
  *  belongs to.  If the package has a single source file, this is the source
  *  file itself; otherwise, this is the directory that contains the source
  * file and its siblings.
  */
case class CopyAction(
  pkg: Package,
  file: File,
  toBucket: String,
  baseKey: String,
  fileKey: String,
  packageKey: String,
  s3version: Option[String] = None
) extends S3Action {
  def copyToKey: String = joinKeys(baseKey, fileKey)
}

case class NoOpAction(
  pkg: Package,
  file: File,
  baseKey: String,
  fileKey: String,
  packageKey: String,
  s3version: Option[String] = None
) extends S3Action {
  def deleteKey: String = joinKeys(baseKey, fileKey)
}
