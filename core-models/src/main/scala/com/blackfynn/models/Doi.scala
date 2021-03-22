package com.blackfynn.models

import io.circe.{ Decoder, Encoder }

case class Doi(value: String) extends AnyVal {

  override def toString = value
}

object Doi {

  implicit val encoder: Encoder[Doi] = Encoder[String].contramap(_.value)
  implicit val decoder: Decoder[Doi] = Decoder[String].map(Doi(_))
}
