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

package com.pennsieve.akka.http

import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.RouteConcatenation._
import akka.http.scaladsl.server.directives.ExecutionDirectives.handleRejections
import com.github.swagger.akka.model.Info
import com.github.swagger.akka.SwaggerHttpService
import io.swagger.models.Scheme
import io.swagger.models.auth.ApiKeyAuthDefinition
import io.swagger.models.auth.In.HEADER
import net.ceedubs.ficus.Ficus._

class SwaggerDocService(
  override val host: String,
  override val schemes: List[Scheme],
  override val apiClasses: Set[Class[_]],
  override val unwantedDefinitions: Seq[String]
) extends SwaggerHttpService
    with RouteService {

  override val basePath = "/" // the basePath for the API you are exposing
  override val apiDocsPath = "api-docs" // where you want the swagger-json endpoint exposed
  override val info = Info() // provides license and other description details
  override val securitySchemeDefinitions = Map(
    "Bearer" -> new ApiKeyAuthDefinition("Authorization", HEADER)
  )
}
