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

package com.pennsieve.models

object PackageTypeInfo {

  /**
    * Given a package type, provide a set of file types the package can be exported to.
    * @return
    */
  def exportableTo: PackageType => Set[FileType] = {
    case PackageType.TimeSeries => Set(FileType.NeuroDataWithoutBorders)
    case _ => Set.empty
  }

  /**
    * Given a package type, provide a set of file types the package can be exported to.
    * @return
    */
  def canExportTo(packageType: PackageType, targetFileType: FileType): Boolean =
    exportableTo(packageType).contains(targetFileType)
}
