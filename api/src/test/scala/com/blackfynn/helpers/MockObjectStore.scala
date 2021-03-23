/**
  * *   Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.
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
    duration: Date
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
