package com.blackfynn.akka

import software.amazon.awssdk.services.sqs.model.Message

package object consumer {

  type MessageExceptionPair = (Message, Throwable)
  type MessagePair[T] = (Message, T)

  type Result[T] = Either[Throwable, T]
  type StreamContext[R, T] = (Message, R, Result[T])
}
