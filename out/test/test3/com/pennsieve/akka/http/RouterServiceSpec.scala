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

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{ Authorization, OAuth2BearerToken }
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.{ RouteTestTimeout, ScalatestRouteTest }
import akka.util.ByteString
import io.circe.Json
import org.scalatest.Suite

import scala.collection.immutable
import scala.concurrent.duration._

trait RouterServiceSpec extends ScalatestRouteTest { self: Suite =>

  implicit val timeout: RouteTestTimeout = RouteTestTimeout(1.minutes)

  var routeService: RouteService

  // http://doc.akka.io/docs/akka-http/10.0.0/scala/http/routing-dsl/testkit.html#testing-sealed-routes
  def routes: Route = Route.seal(routeService.routes)

  def testRequestWithBytes(
    method: HttpMethod,
    path: String,
    content: Option[ByteString] = None,
    session: Option[String] = None,
    headers: immutable.Seq[HttpHeader] = Nil,
    contentType: ContentType = MediaTypes.`application/json`
  ): HttpRequest = {
    val _headers = session match {
      case Some(_session) =>
        headers :+ Authorization(OAuth2BearerToken(_session))
      case None => headers
    }

    val entity = content match {
      case Some(_content) =>
        HttpEntity(contentType, _content)
      case None => HttpEntity.Empty
    }

    HttpRequest(method, uri = path, headers = _headers, entity = entity)
  }

  def testRequest(
    method: HttpMethod,
    path: String,
    json: Option[Json] = None,
    session: Option[String] = None,
    headers: immutable.Seq[HttpHeader] = Nil
  ): HttpRequest =
    testRequestWithBytes(
      method,
      path,
      json.map(j => ByteString(j.toString)),
      session,
      headers
    )
}
