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

package com.pennsieve.domain

import com.pennsieve.models.{ DBPermission, PackageType }
import com.pennsieve.utilities.AbstractError

sealed trait CoreError extends AbstractError

case class ServiceError(message: String) extends CoreError {
  final override def getMessage: String = message
}

case class KeyValueStoreError[T](key: String, value: T) extends CoreError {
  final override def getMessage: String =
    s"Unable to store key: $key, value: $value"
}

case class SqlError(message: String) extends CoreError {
  final override def getMessage: String = message
}

case class IntegrityError(message: String) extends CoreError {
  final override def getMessage: String = message
}

case class InvalidId(message: String) extends CoreError {
  final override def getMessage: String = message
}

case object MissingOrganization extends CoreError {
  final override def getMessage: String =
    s"No organization found"
}

case class InvalidAction(message: String) extends CoreError {
  final override def getMessage: String = message
}

case object OperationNoLongerSupported extends CoreError {
  final override def getMessage: String =
    "This operation is no longer supported"
}

case object PackagePreviewExpected extends CoreError {
  final override def getMessage: String =
    "A package preview must be provided"
}

case class InvalidOrganization(organizationId: Int) extends CoreError {
  final override def getMessage: String =
    s"No organization with id ($organizationId) exists"
}

case class NotFound(id: String) extends CoreError {
  final override def getMessage: String = s"$id not found"
}

case class ExceptionError(exception: Exception) extends CoreError {
  final override def getMessage: String = exception.getMessage

  this.initCause(exception)
}

case class ThrowableError(exception: Throwable) extends CoreError {
  final override def getMessage: String = exception.getMessage

  this.initCause(exception)
}

case class Error(message: String) extends CoreError {
  final override def getMessage: String = message
}

case class InvalidJWT(token: String) extends CoreError {
  final override def getMessage: String = s"Not a JWT: ${token}"
}

case class UnsupportedJWTClaimType(`type`: String) extends CoreError {
  final override def getMessage: String =
    s"Unsupported JWT claim type: ${`type`}"
}

case object MissingTraceId extends CoreError {
  final override def getMessage: String = s"Missing request trace ID"
}

case class PredicateError(message: String) extends CoreError {
  final override def getMessage: String = message
}

case class ParseError(error: io.circe.Error) extends CoreError {
  final override def getMessage: String = error.toString
}

case class PermissionError(
  userId: String,
  permission: DBPermission,
  itemId: String
) extends CoreError {
  final override def getMessage: String =
    s"$userId does not have $permission for $itemId"
}

case class DatasetRolePermissionError(userId: String, datasetId: Int)
    extends CoreError {
  final override def getMessage: String =
    s"$userId does not have permission to access dataset $datasetId"
}

case class LockedDatasetError(datasetId: String) extends CoreError {
  final override def getMessage: String =
    s"dataset $datasetId is locked, so modification is not allowed"
}

case class NoPublicationRequestError(datasetId: String) extends CoreError {
  final override def getMessage: String =
    s"dataset $datasetId currently has no publication request"
}

case class NoRevisionRequestError(datasetId: String) extends CoreError {
  final override def getMessage: String =
    s"dataset $datasetId currently has no revision request"
}

case class OrganizationPermissionError(userId: String, organizationId: Int)
    extends CoreError {
  final override def getMessage: String =
    s"$userId does not have permission to access organization $organizationId"
}

case class SuperPermissionError(userId: String, itemId: String)
    extends CoreError {
  final override def getMessage: String =
    s"$userId does not have super admin for $itemId"
}

case class UnsupportedPackageType(packageType: PackageType) extends CoreError {
  final override def getMessage: String =
    s"operation not supported for the following package type: $packageType"
}

case class FeatureNotEnabled(featureName: String) extends CoreError {
  final override def getMessage: String =
    s"feature not enabled: ${featureName}"
}

case class NameCheckError(
  recommendation: String,
  message: String = "unique naming constraint or naming convention violation"
) extends CoreError {
  final override def getMessage: String = message
}

case class InvalidDateVersion(value: String) extends CoreError {
  final override def getMessage: String = s"Invalid date version: ${value}"
}

case object MissingCustomTermsOfService extends CoreError {
  final override def getMessage: String = s"No custom terms of service found"
}

case object MissingDataUseAgreement extends CoreError {
  final override def getMessage: String = s"No data user agreement found"
}

case class UnauthorizedError(message: String) extends CoreError {
  final override def getMessage: String = message
}

case class StaleUpdateError(message: String) extends CoreError {
  final override def getMessage: String = message
}
