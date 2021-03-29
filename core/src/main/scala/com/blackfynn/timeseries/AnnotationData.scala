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

package com.pennsieve.timeseries

import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization._

sealed trait AnnotationData
case class Text(value: String) extends AnnotationData
case class Integer(value: Int) extends AnnotationData

object AnnotationData {
  implicit val encoder: Encoder[AnnotationData] = deriveEncoder[AnnotationData]
  implicit val decoder: Decoder[AnnotationData] = deriveDecoder[AnnotationData]

  /**
    * Custom serializers for backwards-compatibility with old sphere-json
    * deriveJSON instances.
    *
    * Produces/consumes JSON that looks like:
    *
    *   {"value":"example data","type":"Text"}
    *   {"value":33,"type":"Integer"}
    */
  implicit val jsonFormats: Formats = DefaultFormats + AnnotationDataSerializer

  def serialize(data: AnnotationData): String =
    write(data)

  def deserialize(string: String): AnnotationData =
    parse(string).extract[AnnotationData]

  case object AnnotationDataSerializer
      extends CustomSerializer[AnnotationData](
        _ =>
          ({
            case obj: JObject =>
              val dataType = (obj \ "type").extract[String].toLowerCase()

              dataType match {
                case "text" => Text((obj \ "value").extract[String])
                case "integer" => Integer((obj \ "value").extract[Int])
                case _ =>
                  throw new IllegalArgumentException(
                    s"unsuppported AnnotationData type: $dataType"
                  )
              }
            case unsupportedType =>
              throw new IllegalArgumentException(
                s"unsupported AnnotationData type: $unsupportedType"
              )
          }, {
            case data: AnnotationData =>
              data match {
                case Text(value) =>
                  JObject(("value", JString(value)), ("type", JString("Text")))
                case Integer(value) =>
                  JObject(("value", JInt(value)), ("type", JString("Integer")))
              }
          })
      )
}
