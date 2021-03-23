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

package com.pennsieve.dtos

import com.pennsieve.models.ModelProperty
import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

object ModelPropertyRO {
  def fromRequestObject(p: ModelPropertyRO): ModelProperty = {
    ModelProperty(
      p.key,
      p.value,
      p.dataType.getOrElse("String"),
      p.category.getOrElse("Pennsieve"),
      p.fixed.getOrElse(false),
      p.hidden.getOrElse(false)
    )
  }

  def fromRequestObject(ps: List[ModelPropertyRO]): List[ModelProperty] = {
    ps map fromRequestObject
  }

  implicit val encoder: Encoder[ModelPropertyRO] =
    deriveEncoder[ModelPropertyRO]
  implicit val decoder: Decoder[ModelPropertyRO] =
    deriveDecoder[ModelPropertyRO]
}

case class ModelPropertyRO(
  key: String,
  value: String,
  dataType: Option[String],
  category: Option[String],
  fixed: Option[Boolean],
  hidden: Option[Boolean]
)
