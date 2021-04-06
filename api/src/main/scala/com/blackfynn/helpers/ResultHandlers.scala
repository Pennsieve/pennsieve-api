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

package com.pennsieve.helpers

import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import com.pennsieve.api.Error
import io.circe.Encoder
import io.circe.syntax._
import javax.servlet.http.HttpServletResponse
import org.json4s.JsonAST.JField
import org.json4s.{ Extraction, Formats, JValue }
import org.scalatra._

import scala.concurrent.{ ExecutionContext, Future }

object ResultHandlers {

  def HandleResult[T](
    result: Either[ActionResult, T]
  )(
    successType: (T) => ActionResult
  ): ActionResult =
    result match {
      case Right(r) => successType(r)
      case Left(errorResult) => errorResult
    }

  def toEitherResult[T](e: Either[String, T]): Either[ActionResult, T] = {
    e match {
      case Right(stuff) => Right(stuff)
      case Left(error) => Left(InternalServerError(Error(error)))
    }
  }

  def StreamResult[T, M](
    result: Either[ActionResult, Source[T, M]]
  )(implicit
    response: HttpServletResponse,
    encoder: Encoder[T],
    system: ActorSystem,
    executionContext: ExecutionContext
  ): Future[Any] = { // have to have return type of any here to play nice with scalatra
    result match {
      case Left(error) =>
        Future.successful(error)
      case Right(source) =>
        response.setHeader("Content-Type", "application/json")
        response.setHeader("Transfer-Encoding", "chunked")
        response.setStatus(200)

        val out = response.getWriter
        var first = true

        out.println("[")
        out.flush()
        source
          .runForeach { v =>
            if (first) {
              first = false
              out.print(s"${v.asJson.noSpaces}")
              out.flush()
            } else {
              out.print(",")
              out.println()
              out.print(s"${v.asJson.noSpaces}")
              out.flush()
            }

          }
          .map { _ =>
            out.println("]")
            out.flush()
          }
    }
  }

  def OkResult[T](result: Either[ActionResult, T]): ActionResult =
    HandleResult[T](result)(Ok(_))

  def CreatedResult[T](result: Either[ActionResult, T]): ActionResult =
    HandleResult[T](result)(Created(_))

  def NoContentResult[T](result: Either[ActionResult, T]): ActionResult =
    HandleResult[T](result) { res =>
      ActionResult(204, Unit, headers = Map.empty)
    }

  def OkFilterFieldResult[T](
    result: Either[ActionResult, T],
    filter: Set[String]
  )(implicit
    formats: Formats
  ): ActionResult =
    HandleResult[T](result) { res =>
      Ok(filterFieldsResult[T](res, filter))
    }

  def filterFieldsResult[T](
    result: T,
    filter: Set[String]
  )(implicit
    formats: Formats
  ): JValue = {
    if (filter.isEmpty) Extraction.decompose(result)
    else {
      Extraction
        .decompose(result)
        .removeField {
          case JField(name, _) if filter.contains(name) => false
          case _ => true
        }
    }
  }
}
