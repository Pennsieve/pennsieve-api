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

import akka.http.scaladsl.server.Directives.concat
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.RouteConcatenation._

import cats.implicits._

import com.pennsieve.domain.{ CoreError, Error }

import scala.util.Try

trait RouteService {
  def routes: Route
  def getResult[A](f: Try[Either[CoreError, A]]): Either[CoreError, A] = {
    for {
      ff <- f.toEither.leftMap {
        case t: Throwable => Error(t.getMessage)
      }
      fff <- ff
    } yield fff
  }

  def combine(other: RouteService): RouteService = {
    new RouteService {
      override def routes: Route = this.routes ~ other.routes
    }
  }

}
