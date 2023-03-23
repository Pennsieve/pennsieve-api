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

import java.net.URL
import java.util.Date

import org.scalatra.ActionResult

/**
  * Created by natevecc on 11/14/16.
  */
class MockObjectStore(fileName: String) extends ObjectStore {

  def getPresignedUrl(
    bucket: String,
    key: String,
    duration: Date,
    fileName: String
  ): Either[ActionResult, URL] = {
    Right(new URL(s"file://$bucket/$key"))
  }

  def getListing(
    bucket: String,
    prefix: String
  ): Either[ActionResult, Map[String, Long]] =
    Right(Map(fileName -> 12345L))

  override def getMD5(
    bucket: String,
    key: String
  ): Either[ActionResult, String] = Right("fakemd5")
}
