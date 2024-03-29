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

import java.time.Instant
import java.time.format.DateTimeFormatter

import com.pennsieve.models.ModelProperty
import io.circe.Json.JString
import io.circe._
import cats.implicits._

import scala.util.control.NonFatal

case class ModelPropertyDTO(
  key: String,
  value: Any,
  dataType: String,
  fixed: Boolean,
  hidden: Boolean,
  display: String
)

object ModelPropertyDTO {

  implicit val encoder: Encoder[ModelPropertyDTO] =
    Encoder.instance[ModelPropertyDTO] { modelPropertyDTO =>
      Json.obj(
        "key" -> Json.fromString(modelPropertyDTO.key),
        "value" -> Json.fromString(modelPropertyDTO.value.toString),
        "dataType" -> Json.fromString(modelPropertyDTO.dataType),
        "fixed" -> Json.fromBoolean(modelPropertyDTO.fixed),
        "hidden" -> Json.fromBoolean(modelPropertyDTO.hidden),
        "display" -> Json.fromString(modelPropertyDTO.display)
      )
    }

  implicit val decoder: Decoder[ModelPropertyDTO] =
    Decoder[ModelPropertyDTO] { c =>
      for {
        key <- c.downField("key").as[String]
        dataType <- c.downField("dataType").as[String]
        value <- c.downField("value").as[Any](anyDecoderForType(dataType))
        fixed <- c.downField("fixed").as[Boolean]
        hidden <- c.downField("hidden").as[Boolean]
        display <- c.downField("display").as[String]
      } yield
        ModelPropertyDTO(
          key = key,
          value = value,
          dataType = dataType,
          fixed = fixed,
          hidden = hidden,
          display = display
        )
    }

  def apply(gnp: ModelProperty): ModelPropertyDTO =
    ModelPropertyDTO(
      gnp.key,
      stringToAnyForType(gnp.value, gnp.dataType),
      gnp.dataType,
      gnp.fixed,
      gnp.hidden,
      display(gnp.value, gnp.dataType)
    )

  private def anyDecoderForType(dataType: String): Decoder[Any] =
    (c: HCursor) =>
      c.value match {
        case value if value.isString =>
          value.as[String].map(stringToAnyForType(_, dataType))

        case value if value.isNumber =>
          dataType.toUpperCase match {
            case "INTEGER" => value.as[Int]

            case "DOUBLE" => value.as[Double]

            case unsupportedNumberValue =>
              DecodingFailure(
                s"Could not deserialize $unsupportedNumberValue for $dataType",
                c.history
              ).asLeft[Any]
          }
        case unexpectedValue =>
          DecodingFailure(
            s"Could not deserialize unexpected value type: $unexpectedValue for $dataType",
            c.history
          ).asLeft[DecodingFailure]
      }

  def stringToAnyForType(value: String, dataType: String): Any =
    try {
      dataType.toUpperCase match {
        case "INTEGER" => value.toInt
        case "DOUBLE" => value.toDouble
        case _ => value
      }
    } catch {
      case NonFatal(_) => value
    }

  def display(value: String, dataType: String): String =
    try {
      dataType.toUpperCase match {
        case "INTEGER" | "DOUBLE" => value
        case "DATE" =>
          DateTimeFormatter
            .ofPattern("MMMM dd, YYYY")
            .format(Instant.ofEpochMilli(value.toLong))
        case _ => value
      }
    } catch {
      case NonFatal(_) => value
    }
}
