package com.pennsieve.akka.http

import akka.actor.ActorSystem
import akka.http.scaladsl.Http

import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import com.typesafe.config.{ Config, ConfigFactory }
import net.ceedubs.ficus.Ficus._

import scala.concurrent.ExecutionContextExecutor

trait WebServer {

  def actorSystemName: String

  implicit lazy val system: ActorSystem = ActorSystem(actorSystemName)
  implicit lazy val materializer: ActorMaterializer = ActorMaterializer()

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
