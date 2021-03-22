// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.web

import org.scalatra.swagger.{ ApiInfo, JacksonSwaggerBase, Swagger }
import org.scalatra._

class ResourcesApp(implicit val swagger: Swagger)
    extends ScalatraServlet
    with JacksonSwaggerBase

object PennsieveAppInfo
    extends ApiInfo(
      """Pennsieve Swagger""",
      """Swagger documentation for the Pennsieve api""",
      """http://pennsieve.org""",
      """team@pennsieve.org""",
      """All rights reserved""",
      """http://pennsieve.org"""
    )

class SwaggerApp extends Swagger("2.0", "1.0.0", PennsieveAppInfo)
