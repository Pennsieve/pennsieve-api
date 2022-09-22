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
import akka.http.scaladsl.model.{ HttpHeader, HttpRequest, HttpResponse }
import cats.data.EitherT
import com.pennsieve.discover.client.file.{
  FileClient,
  GetFileFromSourcePackageIdResponse
}

import scala.concurrent.{ ExecutionContext, Future }

class MockDiscoverFileClient(
  implicit
  httpClient: HttpRequest => Future[HttpResponse],
  ec: ExecutionContext,
  system: ActorSystem
) extends FileClient(host = "mock-discover-service-host") {
  override def getFileFromSourcePackageId(
    sourcePackageId: String,
    limit: Option[Int],
    offset: Option[Int],
    headers: List[HttpHeader]
  ): EitherT[Future, Either[Throwable, HttpResponse], GetFileFromSourcePackageIdResponse] = {
    super.getFileFromSourcePackageId(sourcePackageId, limit, offset, headers)
  }
}
