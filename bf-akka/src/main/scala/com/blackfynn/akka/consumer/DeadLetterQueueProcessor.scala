package com.pennsieve.akka.consumer

import akka.{ Done, NotUsed }
import akka.stream.UniqueKillSwitch
import akka.stream.alpakka.sns.scaladsl.SnsPublisher
import akka.stream.alpakka.sqs.{ MessageAction, SqsSourceSettings }
import akka.stream.alpakka.sqs.scaladsl.{ SqsAckSink, SqsSource }
import akka.stream.scaladsl.{ Flow, Keep, RunnableGraph, Sink, Source }
import cats.data.EitherT
import cats.implicits._

import com.pennsieve.models.PayloadType
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sns.model.PublishRequest
import software.amazon.awssdk.services.sqs.model.Message

import com.typesafe.scalalogging.StrictLogging
import io.circe.{ Decoder, Encoder, Json }
import io.circe.syntax._

import scala.concurrent.{ ExecutionContext, Future }

object DeadLetterQueueProcessor extends StrictLogging {

  def victorOpsAlertFlow[T](
    failTask: T => EitherT[Future, Throwable, PayloadType]
  )(implicit
    executionContext: ExecutionContext
  ): Flow[
    Either[MessageExceptionPair, MessagePair[T]],
    (Message, ErrorAlarm),
    NotUsed
  ] =
    Flow[Either[MessageExceptionPair, MessagePair[T]]]
      .mapAsync(1) {
        case Left((message, exception)) =>
          Future.successful(message -> ErrorAlarm(message, exception))
        case Right((message, task)) =>
          failTask(task)
            .map {
              case PayloadType.Upload =>
                message -> ErrorAlarm(
                  s"Message ${message.messageId} Failed Successfully",
                  s"Message was not processed: ${message.body}"
                )
              case PayloadType.Append =>
                message -> ErrorAlarm(
                  s"Message ${message.messageId} Append Failed",
                  s"Message was not processed: ${message.body}"
                )
            }
            .leftMap(exception => message -> ErrorAlarm(message, exception))
            .value
            .map(_.valueOr(identity))
      }

  def deadLetterQueueConsume[T](
    queue: String,
    alertTopic: String,
    sqsSourceSettings: SqsSourceSettings,
    failTask: T => EitherT[Future, Throwable, PayloadType]
  )(implicit
    executionContext: ExecutionContext,
    amazonSQSClient: SqsAsyncClient,
    amazonSNSClient: SnsAsyncClient,
    parserDecoder: Decoder[T]
  ): RunnableGraph[(UniqueKillSwitch, Future[Done])] = {

    val snsFlow = SnsPublisher.publishFlow(alertTopic)

    val ackSink: Sink[MessageAction, Future[Done]] =
      SqsAckSink(queue)

    logger.info(s"polling SQS dead letter queue $queue")

    SqsSource(queue, sqsSourceSettings)
      .map(ProcessorUtilities.parser[T])
      .via(victorOpsAlertFlow(failTask))
      .flatMapConcat {
        case (message, errorAlert) =>
          val messageBody = Json
            .obj("default" -> Json.fromString(errorAlert.asJson.noSpaces))
            .noSpaces
          val snsMessage = PublishRequest
            .builder()
            .message(messageBody)
            .messageStructure("json")
            .build()
          Source
            .single(snsMessage)
            .via(snsFlow)
            .map(_ => MessageAction.Delete(message))
      }
      .viaMat(ProcessorUtilities.killswitch)(Keep.right)
      .toMat(ackSink)(Keep.both)
  }
}
