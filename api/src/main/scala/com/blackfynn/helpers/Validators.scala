// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.helpers

object Validators {

  def greaterThan[T <% Ordered[T]](min: T): T => Either[String, T] =
    (value: T) =>
      if (value > min) Right(value)
      else Left(s"must be greater than $min")

  def greaterThanOrEqualTo[T <% Ordered[T]](min: T): T => Either[String, T] =
    (value: T) =>
      if (value >= min) Right(value)
      else Left(s"must be greater than or equal to $min")

  def lessThan[T <% Ordered[T]](max: T): T => Either[String, T] =
    (value: T) =>
      if (value < max) Right(value)
      else Left(s"must be less than $max")

  def lessThanOrEqualTo[T <% Ordered[T]](max: T): T => Either[String, T] =
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
