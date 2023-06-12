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

import com.pennsieve.dtos.WebhookTargetDTO
import enumeratum.EnumEntry.Uppercase
import enumeratum._
import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

import scala.collection.immutable

sealed trait IntegrationTarget extends EnumEntry with Uppercase

object IntegrationTarget
    extends Enum[IntegrationTarget]
    with CirceEnum[IntegrationTarget] {

  val values: immutable.IndexedSeq[IntegrationTarget] = findValues

  case object PACKAGE extends IntegrationTarget
  case object PACKAGES extends IntegrationTarget
  case object RECORD extends IntegrationTarget
  case object RECORDS extends IntegrationTarget
  case object DATASET extends IntegrationTarget

}
