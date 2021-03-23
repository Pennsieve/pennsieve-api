package com.pennsieve.akka.consumer

import java.io.{ PrintWriter, StringWriter }
import java.time.OffsetDateTime

import software.amazon.awssdk.services.sqs.model.Message
import io.circe.java8.time.JavaTimeEncoders
import io.circe.{ Encoder, Json }

case class ErrorAlarm(
  name: String,
  description: String,
  stateChangeTime: OffsetDateTime = OffsetDateTime.now
) {
  // constant for now. If more values need to be supported in the future make it an ADT/Enum
  val newStateValue: String = "ALARM"
}

object ErrorAlarm extends JavaTimeEncoders {

  def throwableToString(exception: Throwable): String = {
    val sw = new StringWriter
    exception.printStackTrace(new PrintWriter(sw))
    sw.toString
  }

  def apply(message: Message, throwable: Throwable): ErrorAlarm =
    ErrorAlarm(
      s"Message ${message.messageId} Failed",
      s"Message: ${message.body} caused error: ${throwableToString(throwable)}"
    )

  implicit val encoder: Encoder[ErrorAlarm] = (errorAlert: ErrorAlarm) => {
    Json.obj(
      "AlarmName" -> Json.fromString(errorAlert.name),
      "NewStateValue" -> Json.fromString(errorAlert.newStateValue),
      "StateChangeTime" -> encodeOffsetDateTime(errorAlert.stateChangeTime),
      "AlarmDescription" -> Json.fromString(errorAlert.description),
      "state_message" -> Json.fromString(errorAlert.description)
    )
  }
}
