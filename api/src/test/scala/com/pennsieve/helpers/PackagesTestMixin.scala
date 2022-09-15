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

package com.pennsieve.helpers

import com.pennsieve.api.ApiSuite
import com.pennsieve.managers.{ FileManager, PackageManager }
import com.pennsieve.models.PackageState.READY
import com.pennsieve.models.{
  Dataset,
  File,
  FileObjectType,
  FileProcessingState,
  FileType,
  Package,
  PackageType,
  User
}

trait PackagesTestMixin extends DataSetTestMixin {

  self: ApiSuite =>

  // download-manifest tests
  def createTestDownloadPackage(
    name: String,
    parent: Option[Package] = None,
    packageType: PackageType = PackageType.Collection,
    dataset: Dataset = dataset,
    user: User = loggedInUser,
    packageManager: PackageManager = packageManager
  ): Package = {
    packageManager
      .create(name, packageType, READY, dataset, Some(user.id), parent)
      .await match {
      case Left(error) => throw error
      case Right(value) => value
    }
  }

  def createTestDownloadFile(
    fileName: String,
    pkg: Package,
    objectType: FileObjectType = FileObjectType.Source,
    processingState: FileProcessingState = FileProcessingState.Unprocessed,
    fileManager: FileManager = fileManager
  ): File = {
    fileManager
      .create(
        fileName,
        FileType.PDF,
        pkg,
        "s3bucketName",
        fileName,
        objectType = objectType,
        processingState = processingState,
        size = 10
      )
      .await match {
      case Left(error) => throw error
      case Right(value) => value
    }
  }

}
