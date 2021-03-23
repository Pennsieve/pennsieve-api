package com.pennsieve.models

import io.circe.{ Decoder, Encoder }

final case class FilePath(val value: String) extends AnyVal

object FilePath {
  implicit val encoder: Encoder[FilePath] = Encoder[String].contramap(_.value)
  implicit val decoder: Decoder[FilePath] = Decoder[String].map(FilePath.apply)
}
