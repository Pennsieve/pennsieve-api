// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.helpers

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.blackfynn.api.Error
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
    materializer: Materializer,
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
