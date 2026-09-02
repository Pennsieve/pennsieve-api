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

package com.pennsieve.aws.stepfunctions

import com.pennsieve.domain.{
  CoreError,
  ExceptionError,
  ExecutionAlreadyExists
}
import org.scalatest.OptionValues._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import software.amazon.awssdk.services.sfn.SfnAsyncClient
import software.amazon.awssdk.services.sfn.model.{
  ExecutionAlreadyExistsException,
  StartExecutionRequest,
  StartExecutionResponse
}

import java.util.concurrent.CompletableFuture
import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext }

class StepFunctionsSpec extends AnyFlatSpec with Matchers {

  implicit val ec: ExecutionContext = ExecutionContext.global

  def clientFailingWith(exception: Exception): SfnAsyncClient =
    new SfnAsyncClient {
      override def serviceName(): String = "sfn"
      override def close(): Unit = ()
      override def startExecution(
        request: StartExecutionRequest
      ): CompletableFuture[StartExecutionResponse] = {
        val future = new CompletableFuture[StartExecutionResponse]()
        future.completeExceptionally(exception)
        future
      }
    }

  "startExecution" should "translate ExecutionAlreadyExistsException into ExecutionAlreadyExists" in {
    val client = new StepFunctions(
      clientFailingWith(
        ExecutionAlreadyExistsException
          .builder()
          .message("Execution already exists")
          .build()
      )
    )

    val result = Await.result(
      client.startExecution("arn:state-machine", "restore-1-1", "{}").value,
      5.seconds
    )

    result shouldBe Left(ExecutionAlreadyExists("restore-1-1"))
  }

  "startExecution" should "wrap other exceptions as a generic CoreError, not fail silently" in {
    val client =
      new StepFunctions(clientFailingWith(new RuntimeException("boom")))

    val result = Await.result(
      client.startExecution("arn:state-machine", "restore-1-1", "{}").value,
      5.seconds
    )

    result.isLeft shouldBe true
    result.left.toOption.value shouldBe a[ExceptionError]
  }
}
