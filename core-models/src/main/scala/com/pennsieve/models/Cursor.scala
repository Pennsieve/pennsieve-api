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

package com.pennsieve.models

import cats.data._
import cats.implicits._

import io.circe._
import io.circe.syntax._
import io.circe.parser.decode
import java.util.Base64
import java.nio.charset.StandardCharsets

case class SerializedCursor(value: String) extends AnyVal {
  override def toString: String = value
}

/**
  * Opaque cursor used to paginate result sets. Serializable to a base64 string
  * to be returned/accepted by API endpoints.
  */
trait Cursor[A] {

  implicit val encoder: Encoder[A]
  implicit val Decoder: Decoder[A]

  case class CursorException(e: Throwable) extends Throwable {
    final override def getMessage: String = e.getMessage
    this.initCause(e)
  }

  def encodeBase64(c: A): SerializedCursor =
    SerializedCursor(
      Base64.getEncoder
        .encodeToString(c.asJson.noSpaces.getBytes(StandardCharsets.UTF_8))
    )

  def decodeBase64(s: SerializedCursor): Either[CursorException, A] =
    Either
      .catchNonFatal(
        new String(Base64.getDecoder().decode(s.value), StandardCharsets.UTF_8)
      )
      .flatMap(s => decode[A](s))
      .leftMap(CursorException(_))
}
