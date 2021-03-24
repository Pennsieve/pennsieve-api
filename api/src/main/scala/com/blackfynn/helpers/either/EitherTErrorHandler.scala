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

package com.pennsieve.helpers.either

import cats.data._
import cats.implicits._
import com.pennsieve.domain.{
  CoreError,
  DatasetRolePermissionError,
  InvalidAction,
  InvalidId,
  InvalidJWT,
  LockedDatasetError,
  MissingDataUseAgreement,
  MissingTraceId,
  NotFound,
  OperationNoLongerSupported,
  PackagePreviewExpected,
  PermissionError,
  PredicateError,
  StaleUpdateError,
  UnauthorizedError
}
import enumeratum.{ CirceEnum, Enum, EnumEntry }
import org.scalatra._
import com.pennsieve.web.Settings

import scala.collection.immutable
import scala.concurrent.{ ExecutionContext, Future }

sealed trait ErrorResponseType extends EnumEntry

object ErrorResponseType
    extends Enum[ErrorResponseType]
    with CirceEnum[ErrorResponseType] {

  val values: immutable.IndexedSeq[ErrorResponseType] = findValues

  case object BadRequest extends ErrorResponseType
  case object InternalServerError extends ErrorResponseType
  case object NotFound extends ErrorResponseType
  case object Forbidden extends ErrorResponseType
  case object Unauthorized extends ErrorResponseType
  case object PreconditionFailed extends ErrorResponseType
  case object Locked extends ErrorResponseType
}

case class ErrorResponse(
  `type`: String,
  message: String,
  code: Int,
  stackTrace: Option[String]
) {
  def toActionResult(headers: Map[String, String] = Map.empty): ActionResult = {
    ActionResult(this.code, this, headers)
  }
}

object ErrorResponse {
  def apply(
    `type`: ErrorResponseType,
    error: CoreError,
    showStackTrace: Boolean
  ): ErrorResponse = {

    val stackTrace =
      if (showStackTrace)
        Some(error.print(true))
      else
        None

    `type` match {
      case ErrorResponseType.BadRequest =>
        new ErrorResponse(
          `type` = `type`.entryName,
          message = error.getMessage(),
          code = 400,
          stackTrace = stackTrace
        )
      case ErrorResponseType.Unauthorized =>
        new ErrorResponse(
          `type` = `type`.entryName,
          message = error.getMessage(),
          code = 401,
          stackTrace = stackTrace
        )
      case ErrorResponseType.Forbidden =>
        new ErrorResponse(
          `type` = `type`.entryName,
          message = error.getMessage(),
          code = 403,
          stackTrace = stackTrace
        )
      case ErrorResponseType.NotFound =>
        new ErrorResponse(
          `type` = `type`.entryName,
          message = error.getMessage(),
          code = 404,
          stackTrace = stackTrace
        )
      case ErrorResponseType.PreconditionFailed =>
        new ErrorResponse(
          `type` = `type`.entryName,
          message = error.getMessage(),
          code = 412,
          stackTrace = stackTrace
        )
      case ErrorResponseType.Locked =>
        new ErrorResponse(
          `type` = `type`.entryName,
          message = error.getMessage(),
          code = 423,
          stackTrace = stackTrace
        )
      case ErrorResponseType.InternalServerError =>
        new ErrorResponse(
          `type` = `type`.entryName,
          message = error.getMessage(),
          code = 500,
          stackTrace = stackTrace
        )
    }
  }
}

object EitherTErrorHandler {

  object implicits {

    implicit class EitherTCoreErrorHandler[A](
      item: EitherT[Future, CoreError, A]
    ) {

      def orError(
      )(implicit
        ec: ExecutionContext
      ): EitherT[Future, ActionResult, A] = {
        item.leftMap(
          error =>
            ErrorResponse(
              ErrorResponseType.InternalServerError,
              error,
              Settings.respondWithStackTrace
            ).toActionResult()
        )
      }

      def orBadRequest(
      )(implicit
        ec: ExecutionContext
      ): EitherT[Future, ActionResult, A] = {
        item.leftMap(
          error =>
            ErrorResponse(
              ErrorResponseType.BadRequest,
              error,
              Settings.respondWithStackTrace
            ).toActionResult()
        )
      }

      def orNotFound(
      )(implicit
        ec: ExecutionContext
      ): EitherT[Future, ActionResult, A] = {
        item.leftMap(
          error =>
            ErrorResponse(
              ErrorResponseType.NotFound,
              error,
              Settings.respondWithStackTrace
            ).toActionResult()
        )
      }

      def orForbidden(
      )(implicit
        ec: ExecutionContext
      ): EitherT[Future, ActionResult, A] = {
        item.leftMap(
          error =>
            ErrorResponse(
              ErrorResponseType.Forbidden,
              error,
              Settings.respondWithStackTrace
            ).toActionResult()
        )
      }

      def orUnauthorized(
      )(implicit
        ec: ExecutionContext
      ): EitherT[Future, ActionResult, A] = {
        item.leftMap(
          error =>
            ErrorResponse(
              ErrorResponseType.Unauthorized,
              error,
              Settings.respondWithStackTrace
            ).toActionResult()
        )
      }

      def coreErrorToActionResult(
      )(implicit
        ec: ExecutionContext
      ): EitherT[Future, ActionResult, A] = {
        item.leftMap {
          case missingDataUserAgreement: MissingDataUseAgreement.type =>
            ErrorResponse(
              ErrorResponseType.BadRequest,
              missingDataUserAgreement,
              false
            ).toActionResult()
          case error: OperationNoLongerSupported.type =>
            ErrorResponse(ErrorResponseType.Forbidden, error, false)
              .toActionResult()
          case error: PackagePreviewExpected.type =>
            ErrorResponse(ErrorResponseType.Forbidden, error, false)
              .toActionResult()
          case missingTraceId: MissingTraceId.type =>
            ErrorResponse(ErrorResponseType.BadRequest, missingTraceId, false)
              .toActionResult()
          case invalidJwt: InvalidJWT =>
            ErrorResponse(ErrorResponseType.Unauthorized, invalidJwt, false)
              .toActionResult()
          case locked: LockedDatasetError =>
            ErrorResponse(ErrorResponseType.Locked, locked, false)
              .toActionResult()
          case error: InvalidAction =>
            ErrorResponse(ErrorResponseType.Forbidden, error, false)
              .toActionResult()
          case error: PredicateError =>
            ErrorResponse(
              ErrorResponseType.BadRequest,
              error,
              Settings.respondWithStackTrace
            ).toActionResult()
          case error: InvalidId =>
            ErrorResponse(
              ErrorResponseType.BadRequest,
              error,
              Settings.respondWithStackTrace
            ).toActionResult()
          case error: PermissionError =>
            ErrorResponse(
              ErrorResponseType.Forbidden,
              error,
              Settings.respondWithStackTrace
            ).toActionResult()
          case error: DatasetRolePermissionError =>
            ErrorResponse(
              ErrorResponseType.Forbidden,
              error,
              Settings.respondWithStackTrace
            ).toActionResult()
          case error: NotFound =>
            ErrorResponse(
              ErrorResponseType.NotFound,
              error,
              Settings.respondWithStackTrace
            ).toActionResult()
          case error: UnauthorizedError =>
            ErrorResponse(
              ErrorResponseType.Unauthorized,
              error,
              Settings.respondWithStackTrace
            ).toActionResult()
          case staleUpdateError: StaleUpdateError =>
            ErrorResponse(
              ErrorResponseType.PreconditionFailed,
              staleUpdateError,
              false
            ).toActionResult()
          case error =>
            ErrorResponse(
              ErrorResponseType.InternalServerError,
              error,
              Settings.respondWithStackTrace
            ).toActionResult()
        }
      }
    }
  }

}
