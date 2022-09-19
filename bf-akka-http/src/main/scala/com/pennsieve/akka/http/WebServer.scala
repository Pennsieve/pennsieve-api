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

import akka.actor.ActorSystem
import akka.http.scaladsl.Http

import akka.http.scaladsl.server.Route
import com.typesafe.config.{ Config, ConfigFactory }
import net.ceedubs.ficus.Ficus._

import scala.concurrent.ExecutionContextExecutor

trait WebServer {

  def actorSystemName: String

  implicit lazy val system: ActorSystem = ActorSystem(actorSystemName)
  implicit lazy val executionContext: ExecutionContextExecutor =
    system.dispatcher

  lazy val config: Config = ConfigFactory.load()

  val routeService: RouteService

  lazy val routes: Route = Route.seal(routeService.routes)

  val port: Int = config.getOrElse[Int]("port", 8080)
  val host: String = config.getOrElse[String]("host", "0.0.0.0")

  def startServer(): Unit = {
    Http().bindAndHandle(routes, host, port)
    println(s"Server online at http://$host:$port")
  }
}
