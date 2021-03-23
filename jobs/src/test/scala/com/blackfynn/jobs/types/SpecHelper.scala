// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.jobs.types

import akka.NotUsed
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Flow, Sink, Source }
import akka.util.Timeout
import com.pennsieve.aws.queue.{ LocalSQSContainer, SQSDeduplicationContainer }
import com.pennsieve.core.utilities.RedisContainer
import com.pennsieve.jobs._
import com.pennsieve.jobs.container.{ Container, JobContainer }
import com.pennsieve.managers.ManagerSpec
import com.pennsieve.messages._
import com.pennsieve.service.utilities.ContextLogger
import com.typesafe.config.{ Config, ConfigFactory, ConfigValueFactory }
import org.scalatest._
import org.scalatest.EitherValues._
import com.redis.RedisClientPool

import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }

trait SpecHelper extends BeforeAndAfterEach with ManagerSpec {
  self: TestSuite =>

  implicit var jobContainer: Container = _
  implicit val log: ContextLogger = new ContextLogger()

  override def afterStart(): Unit = {
    super.afterStart()
    val config = jobConfig("password")
    jobContainer = new JobContainer(config) with RedisContainer
    with LocalSQSContainer with SQSDeduplicationContainer
  }

  def jobConfig(password: String): Config = {
    ConfigFactory
      .empty()
      .withValue("environment", ConfigValueFactory.fromAnyRef("test"))
      .withValue("parallelism", ConfigValueFactory.fromAnyRef(1))
      .withFallback(postgresContainer.config)
      .withFallback(redisContainer.config)
      .withValue("sqs.host", ConfigValueFactory.fromAnyRef(s""))
      .withValue(
        "sqs.queue",
        ConfigValueFactory.fromAnyRef("queue/test-etl-queue")
      )
      .withValue("sqs.region", ConfigValueFactory.fromAnyRef("us-east-1"))
      .withValue("sqs.deduplication.ttl", ConfigValueFactory.fromAnyRef(2))
      .withValue(
        "sqs.deduplication.redisDBIndex",
        ConfigValueFactory.fromAnyRef(4)
      )
      .withValue(
        "s3.storage_bucket",
        ConfigValueFactory.fromAnyRef("local-test-pennsieve")
      )
  }

  def testFlow[T <: JobResult](
    flow: Flow[BackgroundJob, (BackgroundJob, JobResult), NotUsed],
    job: BackgroundJob
  )(implicit
    mat: ActorMaterializer,
    ec: ExecutionContext
  ): T = {
    val future: Future[Seq[(BackgroundJob, JobResult)]] =
      Source.single(job).via(flow).runWith(Sink.seq)
    val (_, result): (BackgroundJob, JobResult) =
      await[Seq[(BackgroundJob, JobResult)]](future).head

    result.asInstanceOf[T]
  }

  def await[T](
    f: Future[T],
    duration: Duration = Timeout(30 seconds).duration
  ): T =
    Await.result(f, duration)

}
