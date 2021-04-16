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

package com.pennsieve.authorization.routes

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.HttpMethods.GET
import akka.http.scaladsl.model.StatusCodes.OK
import akka.testkit.TestKitBase
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

class AuthenticationRoutesSpec
    extends AuthorizationServiceSpec
    with TestKitBase {

  "GET /authentication route" should {
    "return cognito configuration" in {
      testRequest(GET, s"/authentication/cognito-config") ~>
        routes ~> check {
        status shouldEqual OK
      }
    }
  }
}
