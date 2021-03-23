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
