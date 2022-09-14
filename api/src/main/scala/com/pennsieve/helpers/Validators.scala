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

object Validators {

  def greaterThan[T](
    min: T
  )(implicit
    ev: T => Ordered[T]
  ): T => Either[String, T] =
    (value: T) =>
      if (value > min) Right(value)
      else Left(s"must be greater than $min")

  def greaterThanOrEqualTo[T](
    min: T
  )(implicit
    ev: T => Ordered[T]
  ): T => Either[String, T] =
    (value: T) =>
      if (value >= min) Right(value)
      else Left(s"must be greater than or equal to $min")

  def lessThan[T](
    max: T
  )(implicit
    ev: T => Ordered[T]
  ): T => Either[String, T] =
    (value: T) =>
      if (value < max) Right(value)
      else Left(s"must be less than $max")

  def lessThanOrEqualTo[T](
    max: T
  )(implicit
    ev: T => Ordered[T]
  ): T => Either[String, T] =
    (value: T) =>
      if (value <= max) Right(value)
      else Left(s"must be less than or equal to $max")

  def oneOf[T](options: T*): T => Either[String, T] =
    oneOfTransform(identity[T])(options: _*)

  def oneOfTransform[T](
    transformer: T => T
  )(
    options: T*
  ): T => Either[String, T] =
    (value: T) =>
      if (options.toSet contains transformer(value)) Right(value)
      else Left(s"must be one of ${options.toList.mkString(", ")}")

}
