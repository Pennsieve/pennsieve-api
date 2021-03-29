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
