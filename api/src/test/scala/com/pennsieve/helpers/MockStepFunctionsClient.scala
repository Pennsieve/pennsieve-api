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
import com.pennsieve.domain.{ CoreError, ServiceError }
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse

import scala.collection.mutable
import scala.concurrent.{ ExecutionContext, Future }

class MockStepFunctionsClient extends StepFunctionsClient {

  // (stateMachineArn, executionName, input)
  val startedExecutions: mutable.ArrayBuffer[(String, String, String)] =
    mutable.ArrayBuffer.empty

  private var failNextStart: Boolean = false

  def clear(): Unit = {
    startedExecutions.clear()
    failNextStart = false
  }

  /** The next call to `startExecution` fails, as if Step Functions rejected
    * it (e.g. throttling or a missing IAM grant).
    */
  def failNextStartExecution(): Unit =
    failNextStart = true

  override def startExecution(
    stateMachineArn: String,
    executionName: String,
    input: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, StartExecutionResponse] = {
    if (failNextStart) {
      failNextStart = false
      EitherT.leftT[Future, StartExecutionResponse](
        ServiceError("mock StartExecution failure"): CoreError
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
}
