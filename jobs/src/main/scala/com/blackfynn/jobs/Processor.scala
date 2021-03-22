// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.jobs

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
import com.blackfynn.aws.queue.SQS
import com.blackfynn.jobs.container._
import com.blackfynn.messages._
import com.blackfynn.service.utilities.{ ContextLogger, LogContext, Tier }
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
    mat: ActorMaterializer,
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
  }

}
