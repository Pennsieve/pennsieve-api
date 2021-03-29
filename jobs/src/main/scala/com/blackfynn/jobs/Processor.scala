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

package com.pennsieve.jobs

import akka.actor.ActorSystem
import akka.stream.{ ActorAttributes, Supervision }
import akka.stream.alpakka.sqs.scaladsl.{ SqsAckSink, SqsSource }
import akka.stream.alpakka.sqs.{ MessageAction, SqsSourceSettings }
import akka.stream.contrib.PartitionWith
import akka.stream.scaladsl.GraphDSL.Implicits._
import akka.stream.scaladsl.{
  Flow,
  GraphDSL,
  Merge,
  RunnableGraph,
  Sink,
  Source
}
import akka.stream._
import akka.{ Done, NotUsed }
import cats.implicits._
import software.amazon.awssdk.services.sqs.model.{ Message => SQSMessage }
import com.pennsieve.aws.queue.SQS
import com.pennsieve.jobs.container._
import com.pennsieve.messages._
import com.pennsieve.service.utilities.{ ContextLogger, LogContext, Tier }
import io.circe.parser.decode

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._

object Processor {
  type Processor = Processor.type
  implicit val tier: Tier[Processor] = Tier[Processor]
  val log: ContextLogger = new ContextLogger()

  val resultHandler: Flow[StreamContext, MessageAction, NotUsed] =
    Flow[StreamContext].map {
      case (message, job, Left(exception)) =>
        implicit val context: LogContext = contexts.toContext(job)

        log.tierContext
          .error(s"Failed to run job: ${message.messageId}", exception)

        // let the message go back onto the queue until it is moved to a dead-letter queue
        MessageAction.Ignore(message)

      case (message, job, Right(_)) =>
        implicit val context: LogContext = contexts.toContext(job)

        log.tierContext.info(
          s"Successfully consumed SQS message: ${message.messageId}"
        )

        // finished processing, delete the message from the queue
        MessageAction.Delete(message)
    }

  def parse(
    message: SQSMessage
  ): Either[MessageExceptionPair, MessageJobPair] = {
    val json = message.body

    decode[BackgroundJob](json)
      .map[MessageJobPair] { job =>
        (message, job)
      }
      .leftMap[MessageExceptionPair] { error =>
        (message, JSONParseFailException(json, error.getMessage))
      }
  }

  def killswitch
    : Graph[FlowShape[MessageAction, MessageAction], UniqueKillSwitch] =
    KillSwitches.single[MessageAction]

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
    executor: ProcessJob,
    queue: String,
    parallelism: Int = 10
  )(implicit
    system: ActorSystem,
    executionContext: ExecutionContext,
    container: Container
  ): RunnableGraph[UniqueKillSwitch] = {
    val sqs: SQS = container.sqs

    RunnableGraph
      .fromGraph(GraphDSL.create(killswitch) {
        implicit builder: GraphDSL.Builder[UniqueKillSwitch] => kill =>
          val settings = SqsSourceSettings()
            .withWaitTime(20.seconds)
            .withMaxBufferSize(Math.max(parallelism, 10))
            .withMaxBatchSize(10)

          val messages: Source[SQSMessage, NotUsed] =
            SqsSource(queue, settings)(sqs.client)

          val ack: Sink[MessageAction, Future[Done]] =
            SqsAckSink(queue)(sqs.client)

          val parser = builder.add(PartitionWith.apply(parse))
          val handleResult = builder.add(resultHandler)
          val merge = builder.add(Merge[MessageAction](2))

          val runJob =
            Flow[MessageJobPair]
              .mapAsync(parallelism) {
                case (message, job) =>
                  executor
                    .processJob(job)
                    .value
                    .map(result => (message, job, result))
              }

          val parsedJob = parser.out1
          val failedToParseJob = parser.out0
          val logError =
            Flow[MessageExceptionPair]
              .map {
                case (message, exception) =>
                  log.tierNoContext.error(
                    s"Failed to consume SQS message: ${message.messageId}",
                    exception
                  )

                  // let the message go back onto the queue until it is moved to a dead-letter queue
                  MessageAction.Ignore(message)
              }

          // @formatter:off
          messages.filter(container.deduplicate) ~> parser.in

                 failedToParseJob ~> logError ~> merge
          parsedJob ~> runJob ~> handleResult ~> merge ~> kill ~> ack
          // @formatter:on

          ClosedShape
      })
      .withAttributes(ActorAttributes.supervisionStrategy {
        exception: Throwable =>
          log.noContext.error("Unhandled exception thrown", exception)
          Supervision.resume
      })
  }

}
