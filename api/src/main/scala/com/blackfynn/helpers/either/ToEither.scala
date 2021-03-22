// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.helpers.either

import scala.util.{ Success, Try }
import com.blackfynn.helpers.either.EitherThrowableErrorConverter.implicits._
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
