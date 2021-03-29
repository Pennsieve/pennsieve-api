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

package com.pennsieve.clients

import akka.stream.ActorMaterializer
import com.pennsieve.utilities.Container

trait MockUploadServiceContainer extends UploadServiceContainer {
  self: Container =>

  lazy val uploadServiceConfigPath = "upload_service"

  import net.ceedubs.ficus.Ficus._
  override lazy val materializer: ActorMaterializer =
    ActorMaterializer()
  lazy val uploadServiceHost: String =
    config.as[String](s"$uploadServiceConfigPath.host")

  override val uploadServiceClient: UploadServiceClient =
    new LocalUploadServiceClient()(system, materializer, ec)
}
