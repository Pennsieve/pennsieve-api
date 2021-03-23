// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

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
