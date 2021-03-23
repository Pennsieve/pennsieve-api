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

package com.pennsieve.clients
import java.io.IOException
import java.util.UUID

import cats.implicits._
import com.pennsieve.auth.middleware.Jwt
import com.pennsieve.domain
import com.pennsieve.domain.{ CoreError, ExceptionError, ParseError }
import com.typesafe.scalalogging.LazyLogging
import io.circe._
import io.circe.parser._
import org.apache.http.HttpResponse
import org.apache.http.client.HttpClient
import org.apache.http.client.methods.{
  HttpDelete,
  HttpEntityEnclosingRequestBase,
  HttpGet,
  HttpPost,
  HttpPut,
  HttpRequestBase
}
import org.apache.http.entity.StringEntity
import org.apache.http.util.EntityUtils

trait ToBearer[B] {
  def toBearer(bearer: B): String
}

object ToBearer {
  implicit val tokenToBearer: ToBearer[Jwt.Token] = token => token.value
  implicit val uuidToBearer: ToBearer[UUID] = uuid => uuid.toString
  implicit val stringToBearer: ToBearer[String] = str => str
}

abstract class BaseServiceClient(client: HttpClient) extends LazyLogging {

  private def isSuccess(statusCode: Int): Boolean = (statusCode / 100) == 2

  protected def authorize[B, T <: HttpRequestBase](
    request: T,
    token: B
  )(implicit
    b: ToBearer[B]
  ): T = {
    request.setHeader("Authorization", "Bearer " + b.toBearer(token))
    request
  }

  /**
    * Make a GET, decoding the response with the given decoder.
    *
    * @param token
    * @param request
    * @param body
    * @param b
    * @tparam B
    * @tparam R
    * @return
    */
  protected def makeRequest[B, R <: HttpRequestBase](
    token: B,
    request: R,
    body: Option[Json]
  )(implicit
    b: ToBearer[B]
  ): Either[CoreError, String] = {
    val req: HttpRequestBase = authorize(request, token) match {
      case r: HttpEntityEnclosingRequestBase => {
        if (body.isDefined) {
          val se = new StringEntity(body.get.noSpaces)
          se.setContentType("application/json")
          r.setEntity(se)
        }
        r
      }
      case r => r
    }

    // Using a response handler guarantees that the underlying HTTP connection
    // will be released.
    try {
      client.execute(
        req,
        (response: HttpResponse) => {
          val status = response.getStatusLine.getStatusCode
          val entity = response.getEntity

          if (entity == null) {
            Left(domain.Error("Received null entity"))
          } else {
            val responseString = EntityUtils.toString(entity)
            if (isSuccess(status)) {
              Right(responseString)
            } else {
              Left(domain.Error(responseString))
            }
          }
        }
      )
    } catch {
      case e: IOException => Left(ExceptionError(e))
    }
  }

  /**
    * List `get()`, but discards the response if the request is successful, returning Unit.
    *
    * @param token
    * @param url
    * @tparam B
    * @tparam T
    * @return
    */
  protected def getUnit[B: ToBearer, T: Decoder](
    token: B,
    url: String
  ): Either[CoreError, Unit] =
    makeRequest(token, new HttpGet(url), None).map(_ => ())

  /**
    * Make a GET request, decoding the response with the given decoder.
    *
    * @param token
    * @param url
    * @tparam B
    * @tparam T
    * @return
    */
  protected def get[B: ToBearer, T: Decoder](
    token: B,
    url: String
  ): Either[CoreError, T] =
    makeRequest(token, new HttpGet(url), None).flatMap { response =>
      decode[T](response).leftMap(ParseError)
    }

  /**
    * List `post()`, but discards the response if the request is successful, returning Unit.
    *
    * @param token
    * @param url
    * @param body
    * @tparam B
    * @return
    */
  protected def postUnit[B: ToBearer](
    token: B,
    url: String,
    body: Json
  ): Either[CoreError, Unit] =
    makeRequest(token, new HttpPost(url), Some(body)).map(_ => ())

  /**
    * Make a POST request, decoding the response with the given decoder.
    *
    * @param token
    * @param url
    * @param body
    * @tparam B
    * @tparam T
    * @return
    */
  protected def post[B: ToBearer, T: Decoder](
    token: B,
    url: String,
    body: Json
  ): Either[CoreError, T] =
    makeRequest(token, new HttpPost(url), Some(body)).flatMap { response =>
      decode[T](response).leftMap(ParseError)
    }

  /**
    * List `put()`, but discards the response if the request is successful, returning Unit.
    *
    * @param token
    * @param url
    * @param body
    * @tparam B
    * @return
    */
  protected def putUnit[B: ToBearer](
    token: B,
    url: String,
    body: Json
  ): Either[CoreError, Unit] =
    makeRequest(token, new HttpPut(url), Some(body)).map(_ => ())

  /**
    * Make a PUT request, decoding the response with the given decoder.
    *
    * @param token
    * @param url
    * @param body
    * @tparam B
    * @tparam T
    * @return
    */
  protected def put[B: ToBearer, T: Decoder](
    token: B,
    url: String,
    body: Option[Json]
  ): Either[CoreError, T] =
    makeRequest(token, new HttpPut(url), body).flatMap { response =>
      decode[T](response).leftMap(ParseError)
    }

  /**
    * List `put()`, but discards the response if the request is successful, returning Unit.
    *
    * @param token
    * @param url
    * @param body
    * @tparam B
    * @return
    */
  protected def deleteUnit[B: ToBearer](
    token: B,
    url: String,
    body: Option[Json] = None
  ): Either[CoreError, Unit] =
    makeRequest(token, new HttpDelete(url), body).map(_ => ())

  /**
    * Make a DELETE request, decoding the response with the given decoder.
    *
    * @param token
    * @param url
    * @param body
    * @tparam B
    * @tparam T
    * @return
    */
  protected def delete[B: ToBearer, T: Decoder](
    token: B,
    url: String,
    body: Option[Json] = None
  ): Either[CoreError, T] =
    makeRequest(token, new HttpDelete(url), body).flatMap { response =>
      decode[T](response).leftMap(ParseError)
    }
}
