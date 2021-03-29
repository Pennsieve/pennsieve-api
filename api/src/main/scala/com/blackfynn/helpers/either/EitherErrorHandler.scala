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

import cats.syntax.either._
import com.pennsieve.api.Error
import com.pennsieve.domain.CoreError
import org.scalatra._
import com.pennsieve.web.Settings

object EitherErrorHandler {
  object implicits {

    implicit class EitherErrorHandler[A](item: Option[A]) {

      def orError(message: String): Either[ActionResult, A] = {
        item.toRight(InternalServerError(Error(message)))
      }

      def orBadRequest(
        message: String = "Bad Request."
      ): Either[ActionResult, A] = {
        item.toRight(BadRequest(Error(message)))
      }

      def orNotFound(
        message: String = "Not found."
      ): Either[ActionResult, A] = {
        item.toRight(NotFound(Error(message)))
      }

      def orForbidden(
        message: String = "Forbidden."
      ): Either[ActionResult, A] = {
        item.toRight(Forbidden(Error(message)))
      }

      def orUnauthorized(
        message: String = "Unauthorized."
      ): Either[ActionResult, A] = {
        item.toRight(Unauthorized(Error(message)))
      }
    }

    implicit class EitherCoreErrorHandler[A](item: Either[CoreError, A]) {

      def orError: Either[ActionResult, A] = {
        item.leftMap(
          error =>
            InternalServerError(error.print(Settings.respondWithStackTrace))
        )
      }

      def orBadRequest: Either[ActionResult, A] = {
        item.leftMap(
          error => BadRequest(error.print(Settings.respondWithStackTrace))
        )
      }

      def orNotFound: Either[ActionResult, A] = {
        item.leftMap(
          error => NotFound(error.print(Settings.respondWithStackTrace))
        )
      }

      def orForbidden: Either[ActionResult, A] = {
        item.leftMap(
          error => Forbidden(error.print(Settings.respondWithStackTrace))
        )
      }

      def orUnauthorized: Either[ActionResult, A] = {
        item.leftMap(
          error => Unauthorized(error.print(Settings.respondWithStackTrace))
        )
      }
    }
  }

}
