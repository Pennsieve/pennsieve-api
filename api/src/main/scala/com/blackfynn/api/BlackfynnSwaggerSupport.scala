// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.api

import org.scalatra.ScalatraBase
import org.scalatra.swagger.{ SwaggerSupport, SwaggerSupportSyntax }
import org.scalatra.util.NotNothing

trait PennsieveSwaggerSupport extends SwaggerSupport { self: ScalatraBase =>

  val swaggerTag: String

  override def apiOperation[T](
    nickname: String
  )(implicit
    evidence$21: Predef.Manifest[T],
    evidence$22: NotNothing[T]
  ): SwaggerSupportSyntax.OperationBuilder =
    super
      .apiOperation(nickname)
      .tags(swaggerTag)
      .authorizations("api_key")
}
