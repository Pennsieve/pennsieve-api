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

import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe._
import io.circe.syntax._
import io.circe.parser.decode

import scala.util.Try

/**
  * An integer ID or a node ID. This is primarily used for packages, and
  * occasionally for datasets.
  */
case class ExternalId(value: Either[Int, String]) extends AnyVal {

  def fold[C] = value.fold[C] _

  override def toString = fold(_.toString, _.toString)
}

object ExternalId {

  def intId(id: Int): ExternalId = ExternalId(Left(id))

  def nodeId(id: String): ExternalId = ExternalId(Right(id))

  implicit val encoder: Encoder[ExternalId] =
    Encoder.instance(_.value.fold(_.asJson, _.asJson))

  implicit val decoder: Decoder[ExternalId] = Decoder.decodeJson.emap {
    _.fold[Either[String, ExternalId]](
      Left("null"),
      _ => Left("boolean"),
      jsonNumber => jsonNumber.toInt.toRight("float").map(ExternalId.intId),
      jsonString => Right(ExternalId.nodeId(jsonString)),
      _ => Left("array"),
      _ => Left("object")
    ).left.map(typeName => s"expected integer or string, found $typeName")
  }

  /**
    * Encoders/decoders so that `Map[ExternalId, A]` can be serialized to a JSON object.
    */
  implicit val externalIdKeyEncoder: KeyEncoder[ExternalId] =
    new KeyEncoder[ExternalId] {
      override def apply(externalId: ExternalId): String = externalId.toString
    }

  implicit val ExternalIdKeyDecoder: KeyDecoder[ExternalId] =
    new KeyDecoder[ExternalId] {
      override def apply(key: String): Option[ExternalId] =
        Some(
          Try(key.toInt)
            .map(ExternalId.intId)
            .getOrElse(ExternalId.nodeId(key))
        )
    }
}
