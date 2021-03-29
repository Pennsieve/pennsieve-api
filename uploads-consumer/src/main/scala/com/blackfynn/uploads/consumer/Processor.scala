/*
 * Copyright 2021 University of Pennsylvania
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pennsieve.uploads.consumer

import akka.actor.ActorSystem
import akka.stream.alpakka.sqs.scaladsl.{ SqsAckSink, SqsSource }
import akka.stream.alpakka.sqs.{ MessageAction, SqsSourceSettings }
import akka.stream.contrib.PartitionWith
import akka.stream.scaladsl.GraphDSL.Implicits._
import akka.stream.scaladsl.{
  Flow,
  GraphDSL,
  Keep,
  Merge,
  RunnableGraph,
  Sink,
  Source
}
import akka.stream.{ ActorMaterializer, _ }
import akka.{ Done, NotUsed }
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.{
  Message => SQSMessage,
  SqsException
}
import cats.data._
import cats.implicits._
import com.pennsieve.akka.consumer.{
  MessageExceptionPair,
  ProcessorUtilities,
  StreamContext
}
import com.pennsieve.aws.queue.SQS
import com.pennsieve.models.Manifest
import com.pennsieve.service.utilities.{ ContextLogger, Tier }
import com.pennsieve.uploads.consumer.antivirus.{ Locked, ScanResult, Scanning }

import java.util.concurrent.CompletionException
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

/**
  * Extractor for AWS SqsException. When this exception is raised in the
  * SqsAckSink, Akka wraps it in a CompletionException. This extractor
  * transparently gets the underlying exception.
  */
object WrappedSqsException {
  def unapply(t: Throwable): Option[SqsException] =
    (t, t.getCause) match {
      case (e: SqsException, _) => Some(e)
      case (_: CompletionException, e: SqsException) => Some(e)
      case _ => None
    }
}

object Processor {
  type Processor = Processor.type
  implicit val tier: Tier[Processor] = Tier[Processor]

  /*
   * Reads, parses, and processes messages off of SQS.
   *
   * If another consumer is already processing the message, no action is taken.
   *
   * If the message fails parsing or processing, SQS is told to Ignore the message.
   * Otherwise, SQS is told to Delete the message.
   *
   * Has a killswitch that's used to gracefully shutdown the consumer
   * by allowing it to complete its downstream and cancel its upstream
   */
  def consume(
    queue: String,
    parallelism: Int = 10
  )(implicit
    container: Container,
    executionContext: ExecutionContext,
    materializer: ActorMaterializer,
    system: ActorSystem,
    logger: ContextLogger,
    sqsClient: SqsAsyncClient
  ): RunnableGraph[(UniqueKillSwitch, Future[Done])] = {
    val sqs: SQS = container.sqs

    /**
      * Supervision: ignore SQS errors from increasing the visibility timeout on
      * messages that no longer exist in the queue. This can happen in certain
      * race conditions where two consumers are processing the same message, one
      * finds it locked, but the other consumer finishes processing before the
      * first can increase the visibility timeout.
      */
    val decider: Supervision.Decider = {
      case WrappedSqsException(e)
          if e.statusCode == 400 && e.awsErrorDetails.errorCode == "InvalidParameterValue" =>
        logger.noContext.error(
          s"Supervision: InvalidParameterValue: Message has been deleted from queue ${e.getMessage}"
        )
        Supervision.Resume

      case e =>
        logger.noContext.error(
          s"Supervision: Unknown exception ${e.getMessage}"
        )
        Supervision.Stop
    }

    val ackSink: Sink[MessageAction, Future[Done]] =
      SqsAckSink(queue)(sqs.client)
        .withAttributes(ActorAttributes.supervisionStrategy(decider))

    RunnableGraph
      .fromGraph {
        GraphDSL.create(ProcessorUtilities.killswitch, ackSink)(Keep.both) {
          implicit builder: GraphDSL.Builder[
            (UniqueKillSwitch, Future[Done])
          ] => (kill, ack) =>
            val settings = SqsSourceSettings()
              .withWaitTime(20.seconds)
              .withMaxBufferSize(Math.max(parallelism, 10))
              .withMaxBatchSize(10)

            val sqsSource: Source[SQSMessage, NotUsed] =
              SqsSource(queue, settings)(sqs.client)

            val parser = builder.add(
              PartitionWith.apply(ProcessorUtilities.parser[Manifest])
            )
            val successfulParse = parser.out1
            val failedParse = parser.out0

            val logResult =
              builder.add {
                Flow[StreamContext[Manifest, ScanResult]].map {
                  case (message, job, Left(exception)) =>
                    logger.tierContext.error(
                      s"Failed to consume SQS message: ${message.messageId}. Message Body: ${message.body}",
                      exception
                    )(UploadConsumerLogContext(job))

                    // let the message go back onto the queue until it is moved to a dead-letter queue
                    MessageAction.Ignore(message)

                  case (message, job, Right(Locked)) =>
                    logger.tierContext.info(
                      s"Package locked, delaying SQS message: ${message.messageId}. Message Body: ${message.body}"
                    )(UploadConsumerLogContext(job))

                    // someone else is processing the message - increase the
                    // timeout on the message to allow them to finish
                    // TODO: adjust time here?
                    MessageAction.ChangeMessageVisibility(message, 10.minutes)

                  case (message, job, Right(Scanning)) =>
                    logger.tierContext.info(
                      s"Still scanning, delaying SQS message: ${message.messageId}. Message Body: ${message.body}"
                    )(UploadConsumerLogContext(job))

                    // message is still being scanned, increase timeout of job
                    MessageAction.ChangeMessageVisibility(message, 10.minutes)

                  case (message, job, Right(_)) =>
                    logger.tierContext.info(
                      s"Successfully consumed SQS message: ${message.messageId}. Message Body: ${message.body}"
                    )(UploadConsumerLogContext(job))

                    // finished processing, delete the message from the queue
                    MessageAction.Delete(message)
                }
              }

            val merge = builder.add(Merge[MessageAction](2))

            // Scan uploads in parallel. Each job starts a stream of keep-alive
            // "Scanning" events which are used to tell SQS that the message is still
            // being processed and should not become visible again.
            val moveAndScan =
              Flow[(SQSMessage, Manifest)]
                .flatMapMerge(
                  parallelism, {
                    case (message, job) =>
                      Source
                        .fromFuture(
                          UploadHandler
                            .handle(job)
                            .value
                        )
                        .merge(
                          Source
                            .tick(
                              1.minute,
                              1.minute,
                              (Scanning: ScanResult).asRight[Throwable]
                            ),
                          eagerComplete = true
                        )
                        .map(result => (message, job, result))
                  }
                )

            val handleError =
              Flow[MessageExceptionPair]
                .map {
                  case (message, exception) =>
                    logger.tierNoContext.error(
                      s"Failed to consume SQS message: ${message.messageId}. Message Body: ${message.body}",
                      exception
                    )

                    // let the message go back onto the queue until it is moved to a dead-letter queue
                    MessageAction.Ignore(message)
                }

            // @formatter:off
            sqsSource ~> parser.in

                             failedParse ~> handleError ~> merge ~> kill ~> ack
            successfulParse ~> moveAndScan ~> logResult ~> merge

            // @formatter:on

            ClosedShape
        }
      }
  }
}
