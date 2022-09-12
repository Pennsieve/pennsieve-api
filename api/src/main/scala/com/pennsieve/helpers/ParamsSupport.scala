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

import cats.data.EitherT
import cats.implicits._
import com.pennsieve.api.Error
import com.pennsieve.helpers.Param.ParamKey
import com.pennsieve.helpers.either.EitherThrowableErrorConverter.implicits._
import com.pennsieve.helpers.either.ToEither.implicits._
import enumeratum.{ Enum, EnumEntry }
import io.circe._
import io.circe.parser.decode
import javax.servlet.http.HttpServletRequest
import mouse.all._
import org.joda.time.DateTime
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.scalatra.{ ActionResult, BadRequest, ScalatraBase }

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

trait Param[A] {
  def get(value: String)(implicit key: ParamKey): Either[ActionResult, A]

  /**
    * Derive a Param[B] from a Param[A] via a mapping function. This is named
    * `emap` to match Circe's function of the same name and similar behavior.
    */
  def emap[B](f: A => Either[ActionResult, B]): Param[B] =
    new Param[B] {
      def get(
        value: String
      )(implicit
        key: ParamKey
      ): Either[ActionResult, B] = that.get(value).flatMap(f)
    }

  def map[B](f: A => B): Param[B] =
    new Param[B] {
      def get(
        value: String
      )(implicit
        key: ParamKey
      ): Either[ActionResult, B] = that.get(value).map(f)
    }

  private lazy val that = this
}

object Param {
  type ParamKey = String

  def apply[A](implicit A: Param[A]): Param[A] = A

  implicit val stringParam: Param[String] =
    new Param[String] {
      def get(
        value: String
      )(implicit
        key: ParamKey
      ): Either[ActionResult, String] = Right(value)
    }

  implicit val intParam: Param[Int] =
    new Param[Int] {
      def get(
        value: String
      )(implicit
        key: ParamKey
      ): Either[ActionResult, Int] =
        if (value.matches("-?[0-9]+")) value.parseInt.convertToBadRequest
        else
          Left(BadRequest(Error(s"invalid parameter $key: integer required")))
    }

  implicit val dateTimeParam: Param[DateTime] =
    new Param[DateTime] {
      def get(
        value: String
      )(implicit
        key: ParamKey
      ): Either[ActionResult, DateTime] =
        Either.catchNonFatal(DateTime.parse(value)).convertToBadRequest
    }

  implicit val longParam: Param[Long] =
    new Param[Long] {
      def get(
        value: String
      )(implicit
        key: ParamKey
      ): Either[ActionResult, Long] =
        if (value.matches("-?[0-9]+")) value.parseLong.convertToBadRequest
        else Left(BadRequest(Error(s"invalid parameter $key: long required")))
    }

  implicit val bigIntParam: Param[BigInt] =
    new Param[BigInt] {
      def get(
        value: String
      )(implicit
        key: ParamKey
      ): Either[ActionResult, BigInt] =
        if (value.matches("-?[0-9]+")) Right(BigInt(value))
        else Left(BadRequest(Error(s"invalid parameter $key: bigint required")))
    }

  implicit val booleanParam: Param[Boolean] =
    new Param[Boolean] {
      def get(
        value: String
      )(implicit
        key: ParamKey
      ): Either[ActionResult, Boolean] =
        value.parseBoolean.convertToBadRequest
    }

  implicit def setParam[A](
    implicit
    f: String => Either[ActionResult, Set[A]]
  ): Param[Set[A]] =
    new Param[Set[A]] {
      override def get(
        value: String
      )(implicit
        key: ParamKey
      ): Either[ActionResult, Set[A]] = f(value)
    }

  /**
    * An implicit instance must be constructed for each enum to be decoded.
    */
  def enumParam[A <: EnumEntry](enum: Enum[A]): Param[A] =
    new Param[A] {
      def get(
        value: String
      )(implicit
        key: ParamKey
      ): Either[ActionResult, A] =
        enum
          .withNameInsensitiveOption(value)
          .toRight(
            BadRequest(
              Error(
                s"invalid parameter $key: must be one of ${enum.values.map(_.entryName)}"
              )
            )
          )
    }
}

trait ParamsSupport {
  this: ScalatraBase =>

  type Validator[A] = A => Either[String, A]

  def param[A: Param](
    key: ParamKey,
    validators: Validator[A]*
  )(implicit
    request: HttpServletRequest
  ): Either[ActionResult, A] = {
    implicit val _key = key

    params.get(key) match {
      case None | Some("") =>
        Left(BadRequest(Error(s"missing parameter: $key")))
      case Some(value) => extract(value, validators: _*)
    }
  }

  def paramT[A: Param](
    key: ParamKey,
    validators: Validator[A]*
  )(implicit
    request: HttpServletRequest,
    ec: ExecutionContext
  ): EitherT[Future, ActionResult, A] =
    param[A](key, validators: _*).toEitherT[Future]

  /**
    * paramT with a default value
    */
  def paramT[A: Param](
    key: ParamKey,
    default: A,
    validators: Validator[A]*
  )(implicit
    request: HttpServletRequest,
    ec: ExecutionContext
  ): EitherT[Future, ActionResult, A] = {
    implicit val _key = key

    optParam[A](key, validators: _*)
      .flatMap {
        case Some(value) => Right(value)
        case None => validate(default, validators: _*)
      }
      .toEitherT[Future]
  }

  def optParam[A: Param](
    key: ParamKey,
    validators: Validator[A]*
  )(implicit
    request: HttpServletRequest
  ): Either[ActionResult, Option[A]] = {
    implicit val _key = key

    params.get(key) match {
      case None | Some("") => Right(None)
      case Some(value) =>
        for { result <- extract(value, validators: _*) } yield Some(result)
    }
  }

  def optParamT[A: Param](
    key: ParamKey,
    validators: Validator[A]*
  )(implicit
    request: HttpServletRequest,
    ec: ExecutionContext
  ): EitherT[Future, ActionResult, Option[A]] =
    optParam[A](key, validators: _*).toEitherT[Future]

  def listParam[A: Param](
    key: ParamKey,
    validators: Validator[A]*
  )(implicit
    request: HttpServletRequest
  ): Either[ActionResult, List[A]] = {
    implicit val _key = key

    multiParams
      .getOrElse(key, Seq.empty)
      .toList
      .traverse(extract(_, validators: _*))
  }

  def listParamT[A: Param](
    key: ParamKey,
    validators: Validator[A]*
  )(implicit
    request: HttpServletRequest,
    ec: ExecutionContext
  ): EitherT[Future, ActionResult, List[A]] =
    listParam(key, validators: _*).toEitherT[Future]

  def extract[A: Param](
    value: String,
    validators: Validator[A]*
  )(implicit
    key: ParamKey
  ): Either[ActionResult, A] =
    for {
      parsed <- Param[A].get(value)
      validated <- validate[A](parsed, validators: _*)
    } yield validated

  def extractOrError[T: scala.reflect.Manifest](
    json: JValue
  )(implicit
    jsonFormats: Formats
  ): Either[ActionResult, T] =
    Try(json.extract[T]).orBadRequest

  def extractOrErrorT[T: scala.reflect.Manifest](
    json: JValue
  )(implicit
    jsonFormats: Formats,
    ec: ExecutionContext
  ): EitherT[Future, ActionResult, T] =
    Try(json.extract[T]).orBadRequest
      .toEitherT[Future]

  def decodeOrErrorT[T: Decoder](
    value: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, ActionResult, T] =
    decode[T](value)
      .leftMap(e => BadRequest(e))
      .toEitherT[Future]

  def decodeOrErrorT[T: Decoder](
    json: JValue
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, ActionResult, T] = {
    val s: String = compact(render(json))
    decodeOrErrorT(s)
  }

  def validate[A: Param](
    value: A,
    validators: Validator[A]*
  )(implicit
    key: ParamKey
  ): Either[ActionResult, A] =
    validators.toList match {
      case Nil => Right(value)
      case v :: vs =>
        v(value) match {
          case Left(error) =>
            Left(BadRequest(s"invalid parameter $key: $error"))
          case Right(_) => validate[A](value, vs: _*)
        }
    }
}
