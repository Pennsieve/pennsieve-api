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
import org.scalatra.{ ActionResult, BadRequest }

case class EitherThrowableErrorConverter[A](item: Either[Throwable, A]) {

  def convertToBadRequest: Either[ActionResult, A] =
    item.leftMap(throwable => BadRequest(Error(throwable.getMessage)))
}

object EitherThrowableErrorConverter {
  object implicits {
    implicit def eitherWithThrowableHandler[A](
      e: Either[Throwable, A]
    ): EitherThrowableErrorConverter[A] =
      EitherThrowableErrorConverter(e)
  }
}
