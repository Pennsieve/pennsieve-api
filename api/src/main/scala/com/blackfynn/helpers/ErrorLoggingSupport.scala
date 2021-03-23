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

import java.io.{ PrintWriter, StringWriter }
import javax.servlet.http.HttpServletRequest

import com.typesafe.scalalogging.LazyLogging
import org.scalatra.{ ErrorHandler, ScalatraBase }
import org.slf4j.MDC

/**
  * Inspired by: https://github.com/scalatra/scalatra/blob/2.4.x/slf4j/src/main/scala/org/scalatra/slf4j/ScalatraSlf4jRequestLogging.scala
  */
trait ErrorLoggingSupport extends ScalatraBase with LazyLogging {

  def putToMDC = {
    MDC.clear()
    MDC.put("METHOD", request.getMethod)
    MDC.put("URI", request.getRequestURI)
  }

  def logRequest(request: HttpServletRequest): Unit = {
    putToMDC
    MDC.put("STATUS", status.toString)
    logger.error(s"""Request Error -
         |Method: ${request.getMethod},
         |Status: $status,
         |URI: ${request.getRequestURI}""".stripMargin)
    MDC.clear()
  }

  var loggerErrorHandler: ErrorHandler = {
    case ex: Throwable =>
      val sw = new StringWriter
      ex.printStackTrace(new PrintWriter(sw))
      putToMDC
      logger.error(s"""Unhandled Error -
           |Method: ${request.getMethod},
           |URI: ${request.getRequestURI},
           |StackTrace: $sw""".stripMargin)
      throw ex
  }

  errorHandler = loggerErrorHandler

  /*
   Hack to get logging working: status isn't set on the response object until
   after renderResponse is done. By plugging the logging in right after the response is
   set we guarantee the status is set.
   */
  override protected def renderResponse(actionResult: Any): Unit = {
    super.renderResponse(actionResult)
    if (status >= 500) logRequest(request)
  }
}
