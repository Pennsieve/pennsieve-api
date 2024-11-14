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

package com.pennsieve.core.utilities

import cats.data._
import cats.implicits._
import com.pennsieve.domain.{ CoreError, ExceptionError, UsernameExistsError }
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException

import scala.concurrent.{ ExecutionContext, Future }

object FutureEitherHelpers {

  def unit[T](implicit ec: ExecutionContext): EitherT[Future, T, Unit] =
    EitherT.rightT[Future, T](())

  def assert[T](
    predicate: => Boolean
  )(
    error: T
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, T, Unit] =
    if (predicate) unit else EitherT.leftT[Future, Unit](error)

  object implicits {

    implicit class FutureEitherT[A](f: Future[A]) {
      def toEitherT[B](
        exceptionHandler: Exception => B
      )(implicit
        ec: ExecutionContext
      ): EitherT[Future, B, A] = {
        val futureEither: Future[Either[B, A]] = f
          .map { value =>
            Right(value)
          }
          .recover {
            case e: Exception => Left(exceptionHandler(e))
          }

        EitherT(futureEither)
      }

      def toEitherT(
        implicit
        ec: ExecutionContext
      ): EitherT[Future, CoreError, A] =
        f.toEitherT[CoreError] {
          case e: CoreError => e
          case e: Exception => ExceptionError(e)
        }
    }

    implicit class FutureEitherEitherT[A, B](f: Future[Either[B, A]]) {
      def toEitherT(
        exceptionHandler: Exception => B
      )(implicit
        ec: ExecutionContext
      ): EitherT[Future, B, A] = {
        val futureEither: Future[Either[B, A]] = f.recover {
          case e: Exception => Left(exceptionHandler(e))
        }

        EitherT(futureEither)
      }
    }

    implicit class FutureOption[A](f: Future[Option[A]]) {

      def whenNone[B](
        l: B
      )(implicit
        ec: ExecutionContext
      ): EitherT[Future, B, A] = {
        val e = f.map {
          case None => Left(l)
          case Some(r) => Right(r)
        }

        EitherT(e)
      }
    }

    implicit class ListEitherTFuture[A, B](l: List[EitherT[Future, A, B]]) {
      def separate(
        implicit
        ec: ExecutionContext
      ): Future[(List[A], List[B])] = {
        Future.sequence(l.map(_.value)).map(x => x.separate)
      }

      def separateE(
        implicit
        ec: ExecutionContext
      ): EitherT[Future, CoreError, (List[A], List[B])] = {
        l.separate.toEitherT
      }

    }
  }

}
