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

object ModelProperty {

  def created: ModelProperty =
    ModelProperty(
      "created",
      System.currentTimeMillis.toString,
      "date",
      "Pennsieve",
      true,
      false
    )

  def merge(
    original: List[ModelProperty],
    updated: List[ModelProperty]
  ): List[ModelProperty] = {
    // Only overwrite original properties that have the same category and key
    val untouched = original.filterNot(o => {
      updated.exists(u => u.category == o.category && u.key == o.key)
    })

    untouched ++ updated.filterNot(p => p.value.isEmpty)
  }

  /**
    * Construct default model properties for a package. This is used when
    * creating packages in ETL.
    */
  def fromFileTypeInfo(fileTypeInfo: FileTypeInfo): List[ModelProperty] =
    List(
      ModelProperty(
        "subtype",
        fileTypeInfo.packageSubtype,
        "string",
        fixed = false,
        hidden = true
      ),
      ModelProperty(
        "icon",
        fileTypeInfo.icon.toString,
        "string",
        fixed = false,
        hidden = true
      )
    )

  implicit val encoder: Encoder[ModelProperty] =
    deriveEncoder[ModelProperty]
  implicit val decoder: Decoder[ModelProperty] =
    deriveDecoder[ModelProperty]

}

case class ModelProperty(
  key: String,
  value: String,
  dataType: String = "String",
  category: String = "Pennsieve",
  fixed: Boolean = false,
  hidden: Boolean = false
)
