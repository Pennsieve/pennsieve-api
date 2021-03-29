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

package com.pennsieve.admin.api.dtos

import com.pennsieve.models.Organization
import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

case class SimpleOrganizationDTO(nodeId: String, name: String, id: Int)

object SimpleOrganizationDTO {
  def apply(organization: Organization): SimpleOrganizationDTO = {
    SimpleOrganizationDTO(
      nodeId = organization.nodeId,
      name = organization.name,
      id = organization.id
    )
  }

  implicit val encoder: Encoder[SimpleOrganizationDTO] =
    deriveEncoder[SimpleOrganizationDTO]
  implicit val decoder: Decoder[SimpleOrganizationDTO] =
    deriveDecoder[SimpleOrganizationDTO]
}
