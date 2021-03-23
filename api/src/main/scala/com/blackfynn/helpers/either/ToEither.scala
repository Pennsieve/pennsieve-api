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

import scala.util.{ Success, Try }
import com.pennsieve.helpers.either.EitherThrowableErrorConverter.implicits._
import org.json4s.JValue
import org.scalatra.ActionResult

case class ToEither[T](t: Try[T]) {

  def toEither: Either[Throwable, T] =
    t.transform(s => Success(Right(s)), f => Success(Left(f))).get

  def orBadRequest: Either[ActionResult, T] =
    ToEither(t).toEither.convertToBadRequest

}

object ToEither {
  object implicits {
    implicit def toEither[T](t: Try[T]): ToEither[T] =
      ToEither(t)
  }

}
