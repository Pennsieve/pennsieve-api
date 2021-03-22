package com.blackfynn.clients

import com.blackfynn.aws.s3.S3Trait
import java.time.{ ZoneOffset, ZonedDateTime }
import scala.collection.mutable.HashMap
import java.security.MessageDigest

import com.blackfynn.models.ToDateVersion
import com.blackfynn.models.{ DateVersion, ToDateVersion }

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
