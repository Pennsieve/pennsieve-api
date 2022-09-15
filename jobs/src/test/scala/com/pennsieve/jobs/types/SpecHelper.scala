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

package com.pennsieve.jobs.types

import akka.actor.ActorSystem
import akka.NotUsed
import akka.stream.scaladsl.{ Flow, Sink, Source }
import akka.util.Timeout
import com.pennsieve.aws.queue.LocalSQSContainer
import com.pennsieve.aws.sns.LocalSNSContainer
import com.pennsieve.jobs._
import com.pennsieve.jobs.container.{ Container, JobContainer }
import com.pennsieve.managers.ManagerSpec
import com.pennsieve.messages._
import com.pennsieve.service.utilities.ContextLogger
import com.typesafe.config.{ Config, ConfigFactory, ConfigValueFactory }
import org.scalatest._
import org.scalatest.EitherValues._

import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }

trait SpecHelper extends BeforeAndAfterEach with ManagerSpec {
  self: TestSuite =>

  implicit var jobContainer: Container = _
  implicit val log: ContextLogger = new ContextLogger()

  override def afterStart(): Unit = {
    super.afterStart()
    val config = jobConfig("password")
    jobContainer = new JobContainer(config) with LocalSQSContainer
    with LocalSNSContainer
  }

  def jobConfig(password: String): Config = {
    ConfigFactory
      .empty()
      .withValue("environment", ConfigValueFactory.fromAnyRef("test"))
      .withValue("parallelism", ConfigValueFactory.fromAnyRef(1))
      .withFallback(postgresContainer.config)
      .withValue("sqs.host", ConfigValueFactory.fromAnyRef(s""))
      .withValue(
        "sqs.queue",
        ConfigValueFactory.fromAnyRef("queue/test-etl-queue")
      )
      .withValue("sqs.region", ConfigValueFactory.fromAnyRef("us-east-1"))
      .withValue(
        "s3.storage_bucket",
        ConfigValueFactory.fromAnyRef("local-test-pennsieve")
      )
      .withValue(
        "sns.host",
        ConfigValueFactory.fromAnyRef(s"https://localhost")
      )
      .withValue("sns.region", ConfigValueFactory.fromAnyRef("us-east-1"))
  }

  def testFlow[T <: JobResult](
    flow: Flow[BackgroundJob, (BackgroundJob, JobResult), NotUsed],
    job: BackgroundJob
  )(implicit
    system: ActorSystem,
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
