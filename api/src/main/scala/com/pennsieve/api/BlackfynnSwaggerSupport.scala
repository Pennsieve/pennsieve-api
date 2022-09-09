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

package com.pennsieve.api

import org.scalatra.ScalatraBase
import org.scalatra.swagger.{ SwaggerSupport, SwaggerSupportSyntax }
import org.scalatra.util.NotNothing

trait PennsieveSwaggerSupport extends SwaggerSupport { self: ScalatraBase =>

  //TODO: Investigate if this is still necessary with Scalatra >= 2.7.x.
  // They introduced a Option[String] swaggerTag in SwaggerSupportSyntax <: SwaggerSupport
  val pennsieveSwaggerTag: String

  override def apiOperation[T](
    nickname: String
  )(implicit
    evidence$21: Predef.Manifest[T],
    evidence$22: NotNothing[T]
  ): SwaggerSupportSyntax.OperationBuilder =
    super
      .apiOperation(nickname)
      .tags(pennsieveSwaggerTag)
      .authorizations("api_key")
}
