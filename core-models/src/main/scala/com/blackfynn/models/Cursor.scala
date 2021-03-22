package com.blackfynn.models

import cats.data._
import cats.implicits._

import io.circe._
import io.circe.syntax._
import io.circe.parser.decode
import java.util.Base64
import java.nio.charset.StandardCharsets

case class SerializedCursor(value: String) extends AnyVal {
  override def toString: String = value
}

/**
  * Opaque cursor used to paginate result sets. Serializable to a base64 string
  * to be returned/accepted by API endpoints.
  */
trait Cursor[A] {

  implicit val encoder: Encoder[A]
  implicit val Decoder: Decoder[A]

  case class CursorException(e: Throwable) extends Throwable {
    final override def getMessage: String = e.getMessage
    this.initCause(e)
  }

  def encodeBase64(c: A): SerializedCursor =
    SerializedCursor(
      Base64.getEncoder
        .encodeToString(c.asJson.noSpaces.getBytes(StandardCharsets.UTF_8))
    )

  def decodeBase64(s: SerializedCursor): Either[CursorException, A] =
    Either
      .catchNonFatal(
        new String(Base64.getDecoder().decode(s.value), StandardCharsets.UTF_8)
      )
      .flatMap(s => decode[A](s))
      .leftMap(CursorException(_))
}
