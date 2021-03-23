// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

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
