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

package com.pennsieve.web

import org.scalatra.swagger.{
  ApiInfo,
  ContactInfo,
  JacksonSwaggerBase,
  LicenseInfo,
  Swagger
}
import org.scalatra._

class ResourcesApp(implicit val swagger: Swagger)
    extends ScalatraServlet
    with JacksonSwaggerBase

object PennsieveAppInfo
    extends ApiInfo(
      """Pennsieve Swagger""",
      """Swagger documentation for the Pennsieve api""",
      """https://docs.pennsieve.io/docs/pennsieve-terms-of-service""",
      ContactInfo("Pennsieve Team", "https://docs.pennsieve.io", "support@pennsieve.net"),
      LicenseInfo("All rights reserved", "https://docs.pennsieve.io")
    )

class SwaggerApp extends Swagger("2.0", "1.0.0", host="api.pennsieve.io", apiInfo = PennsieveAppInfo)
