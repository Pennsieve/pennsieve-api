package com.pennsieve.akka.consumer

import akka.stream.alpakka.sqs.MessageAction
import akka.stream.{ FlowShape, Graph, KillSwitches, UniqueKillSwitch }
import cats.syntax.either._
import io.circe.Decoder
import io.circe.parser.decode
import software.amazon.awssdk.services.sqs.model.Message

object ProcessorUtilities {

  def parser[T: Decoder](
    message: Message
  ): Either[MessageExceptionPair, MessagePair[T]] = {
    decode[T](message.body)
      .map[MessagePair[T]] { job =>
        (message, job)
      }
      .leftMap[MessageExceptionPair] { exception =>
        (message, exception)
      }
  }

  def killswitch
    : Graph[FlowShape[MessageAction, MessageAction], UniqueKillSwitch] =
    KillSwitches.single[MessageAction]
}
