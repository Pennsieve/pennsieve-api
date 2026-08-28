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

package com.pennsieve.helpers

import cats.data.EitherT
import com.pennsieve.aws.stepfunctions.StepFunctionsClient
import com.pennsieve.domain.{ CoreError, ExecutionAlreadyExists }
import software.amazon.awssdk.services.sfn.model.{
  DescribeExecutionResponse,
  ExecutionStatus,
  StartExecutionResponse
}

import scala.collection.mutable
import scala.concurrent.{ ExecutionContext, Future }

class MockStepFunctionsClient extends StepFunctionsClient {

  // (stateMachineArn, executionName, input)
  val startedExecutions: mutable.ArrayBuffer[(String, String, String)] =
    mutable.ArrayBuffer.empty

  private var duplicateExecutionNames: Set[String] = Set.empty
  private var nextDescribeStatus: ExecutionStatus = ExecutionStatus.RUNNING

  def clear(): Unit = {
    startedExecutions.clear()
    duplicateExecutionNames = Set.empty
    nextDescribeStatus = ExecutionStatus.RUNNING
  }

  /** The next call to `startExecution` with this name fails as if a Step
    * Functions execution with that name were already running.
    */
  def failNextStartWithDuplicateName(executionName: String): Unit =
    duplicateExecutionNames += executionName

  def withNextDescribeExecutionStatus(status: ExecutionStatus): Unit =
    nextDescribeStatus = status

  override def startExecution(
    stateMachineArn: String,
    executionName: String,
    input: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, StartExecutionResponse] = {
    if (duplicateExecutionNames.contains(executionName)) {
      EitherT.leftT[Future, StartExecutionResponse](
        ExecutionAlreadyExists(executionName): CoreError
      )
    } else {
      startedExecutions += ((stateMachineArn, executionName, input))
      EitherT.rightT[Future, CoreError](
        StartExecutionResponse
          .builder()
          .executionArn(s"$stateMachineArn:$executionName")
          .build()
      )
    }
  }

  override def describeExecution(
    executionArn: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, DescribeExecutionResponse] =
    EitherT.rightT[Future, CoreError](
      DescribeExecutionResponse
        .builder()
        .executionArn(executionArn)
        .status(nextDescribeStatus)
        .build()
    )
}
