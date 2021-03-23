package com.pennsieve.core.clients

import akka.http.scaladsl.model.{ HttpCharsets, MediaTypes }
import akka.http.scaladsl.unmarshalling.{ FromEntityUnmarshaller, Unmarshaller }
import akka.http.scaladsl.util.FastFuture
import io.circe.jawn

object AkkaHttpImplicits {
  implicit final val structuredJsonEntityUnmarshaller
    : FromEntityUnmarshaller[io.circe.Json] =
    Unmarshaller.byteStringUnmarshaller
      .forContentTypes(MediaTypes.`application/json`)
      .flatMapWithInput { (httpEntity, byteString) =>
        if (byteString.isEmpty) {
          FastFuture.failed(Unmarshaller.NoContentException)
        } else {
          val parseResult =
            Unmarshaller.bestUnmarshallingCharsetFor(httpEntity) match {
              case HttpCharsets.`UTF-8` =>
                jawn.parse(byteString.utf8String)
              case otherCharset =>
                jawn.parse(
                  byteString.decodeString(otherCharset.nioCharset.name)
                )
            }
          parseResult.fold(FastFuture.failed, FastFuture.successful)
        }
      }
}
