// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

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
