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

import com.pennsieve.aws.s3.S3Trait
import java.time.{ ZoneOffset, ZonedDateTime }
import scala.collection.mutable.HashMap
import java.security.MessageDigest

import com.pennsieve.models.ToDateVersion
import com.pennsieve.models.{ DateVersion, ToDateVersion }

class MockCustomTermsOfServiceClient extends CustomTermsOfServiceClient {

  private def md5(s: String): String = {
    MessageDigest
      .getInstance("MD5")
      .digest(s.getBytes)
      .map("%02X".format(_))
      .mkString
  }

  val bucket: HashMap[String, HashMap[DateVersion, String]] = HashMap()

  def reset(): Unit = {
    this.bucket.clear()
  }

  override def getTermsOfService[V](
    organizationId: String,
    version: V
  )(implicit
    tdv: ToDateVersion[V]
  ): Either[Throwable, String] = {
    val v = tdv.toDateVersion(version)
    for {
      entries <- bucket
        .get(organizationId)
        .toRight(new Throwable(s"${organizationId}: not found"))
      terms <- entries.get(v).toRight(new Throwable(s"${v}: terms not found"))
    } yield terms
  }

  override def updateTermsOfService[V](
    organizationId: String,
    text: String,
    version: V = ZonedDateTime.now(ZoneOffset.UTC)
  )(implicit
    tdv: ToDateVersion[V]
  ): Either[Throwable, (DateVersion, ETag, MD5)] = {
    val v = tdv.toDateVersion(version)
    val entries = bucket.getOrElseUpdate(organizationId, HashMap())
    entries(v) = text
    Right((v, ETag(v.toString), MD5(md5(text))))
  }
}

trait MockCustomTermsOfServiceClientContainer
    extends CustomTermsOfServiceClientContainer {
  val s3: S3Trait
  val customTermsOfServiceClient = new MockCustomTermsOfServiceClient
}
