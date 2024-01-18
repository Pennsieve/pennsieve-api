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

import cats.implicits._
import io.circe.generic.extras._
import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.syntax._
import io.circe.{ HCursor, Json }

object OrcidActivity {
  def WORK = "Work"
}

// TODO: the JSON Decoders here are fictitious (we are not decoding ORCID Work records -- yet)

case class OrcidExternalId(
  externalIdType: String,
  externalIdValue: String,
  externalIdUrl: OrcidTitleValue,
  externalIdRelationship: String
)

object OrcidExternalId {
  implicit val encoder: Encoder[OrcidExternalId] =
    new Encoder[OrcidExternalId] {
      final def apply(a: OrcidExternalId): Json = Json.obj(
        ("external-id-type", Json.fromString(a.externalIdType)),
        ("external-id-value", Json.fromString(a.externalIdValue)),
        ("external-id-relationship", Json.fromString(a.externalIdRelationship)),
        ("external-id-url", a.externalIdUrl.asJson)
      )
    }

  implicit val decoder: Decoder[OrcidExternalId] =
    deriveDecoder[OrcidExternalId]
}

case class OricdExternalIds(externalId: Seq[OrcidExternalId])

object OricdExternalIds {
  implicit val encoder: Encoder[OricdExternalIds] =
    new Encoder[OricdExternalIds] {
      final def apply(a: OricdExternalIds): Json =
        Json.obj(("external-id", a.externalId.asJson))
    }

  implicit val decoder: Decoder[OricdExternalIds] =
    deriveDecoder[OricdExternalIds]
}

case class OrcidTitle(title: OrcidTitleValue, subtitle: OrcidTitleValue)

object OrcidTitle {
  implicit val encoder: Encoder[OrcidTitle] = deriveEncoder[OrcidTitle]
  implicit val decoder: Decoder[OrcidTitle] = deriveDecoder[OrcidTitle]
}

case class OrcidTitleValue(value: String)
object OrcidTitleValue {
  implicit val encoder: Encoder[OrcidTitleValue] =
    deriveEncoder[OrcidTitleValue]
  implicit val decoder: Decoder[OrcidTitleValue] =
    deriveDecoder[OrcidTitleValue]
}

case class OrcidWork(
  title: OrcidTitle,
  `type`: String,
  externalIds: OricdExternalIds,
  url: OrcidTitleValue
)

object OrcidWork {
  implicit val encoder: Encoder[OrcidWork] = new Encoder[OrcidWork] {
    final def apply(a: OrcidWork): Json = Json.obj(
      ("title", a.title.asJson),
      ("type", Json.fromString(a.`type`)),
      ("external-ids", a.externalIds.asJson),
      ("url", a.url.asJson)
    )
  }
  implicit val decoder: Decoder[OrcidWork] = deriveDecoder[OrcidWork]
}
