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
import org.scalatra.{ ActionResult, InternalServerError }

/**
  * Helper methods for converting Either's with the left set as string to ActionResult/Error
  * objects
  */
case class EitherStringErrorConverter[A](item: Either[String, A]) {

  /**
    * Converts a string to a proper InternalServerError (ActionResult) with the body
    * set to an Error object with the original string as the message inside
    *
    * @return the string wrapped in an Error object wrapped in an InternalServerError
    */
  def convertToError: Either[ActionResult, A] =
    item.leftMap(msg => InternalServerError(Error(msg)))
}

object EitherStringErrorConverter {
  object implicits {
    implicit def eitherWithStringHandler[A](
      e: Either[String, A]
    ): EitherStringErrorConverter[A] =
      EitherStringErrorConverter(e)
  }
}
