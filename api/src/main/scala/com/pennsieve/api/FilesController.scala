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

package com.pennsieve.api

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.{ Authorization, OAuth2BearerToken }
import cats.data.{ EitherT, _ }
import cats.implicits._
import com.pennsieve.audit.middleware.{ Auditor, TraceId }
import com.pennsieve.auth.middleware.{ DatasetPermission, Jwt }
import com.pennsieve.core.utilities
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.core.utilities.{
  checkOrErrorT,
  getFileType,
  splitFileName,
  JwtAuthenticator
}
import com.pennsieve.domain.{
  CoreError,
  IntegrityError,
  InvalidAction,
  InvalidId,
  OperationNoLongerSupported,
  PackagePreviewExpected,
  ServiceError
}
import com.pennsieve.dtos.Builders._
import com.pennsieve.dtos._
import com.pennsieve.helpers.APIContainers.{
  InsecureAPIContainer,
  SecureAPIContainer,
  SecureContainerBuilderType
}
import com.pennsieve.helpers.ObjectStore
import com.pennsieve.helpers.ResultHandlers._
import com.pennsieve.helpers.either.EitherTErrorHandler.implicits._
import com.pennsieve.managers.FileManager
import com.pennsieve.managers.FileManager.UploadSourceFile
import com.pennsieve.models.{
  ChangelogEventDetail,
  CollectionUpload,
  Dataset,
  ExternalId,
  FileChecksum,
  FileState,
  FileTypeInfo,
  JobId,
  Manifest,
  Organization,
  Package,
  PackageState,
  PackageType,
  PayloadType,
  Role,
  Upload,
  User
}
import com.pennsieve.uploads._
import com.pennsieve.models.Utilities.cleanS3Key
import com.pennsieve.web.Settings
import org.scalatra._
import org.scalatra.swagger.Swagger
import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder

import java.util.UUID
import javax.servlet.http.HttpServletRequest
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

case class PreviewPackageRequest(files: List[S3File])

case class PreviewPackageResponse(packages: List[PackagePreview])

object FilesController {
  def manifestUploadKey(email: String, importId: JobId): String = {
    s"job-manifests/$email/$importId/manifest.json"
  }

  def storageDirectory(user: User, importId: String) =
    s"${user.email}/data/$importId/"

  def uploadsDirectory(
    user: User,
    importId: String,
    usingUploadService: Boolean
  ) =
    if (usingUploadService) s"${user.id}/$importId/"
    else s"${user.email}/$importId/"

  /**
    * Determines S3 location for uploaded files
    *
    * @param file: actual file name/folder name on platform
    * @param user
    * @param importId
    * @param hasPreview
    * @param usingUploadService
    * @return
    */
  def generateS3Key(
    file: String,
    user: User,
    importId: String,
    hasPreview: Boolean,
    usingUploadService: Boolean
  ): String = {
    if (hasPreview) {
      s"${FilesController
        .uploadsDirectory(user, importId, usingUploadService)}${cleanS3Key(file)}"
    } else {
      cleanS3Key(file)
    }
  }
}
