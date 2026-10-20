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

import cats.data.EitherT
import software.amazon.awssdk.services.sfn.SfnAsyncClient
import software.amazon.awssdk.services.sfn.model.{
  StartExecutionRequest,
  StartExecutionResponse
}
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits.FutureEitherT
import com.pennsieve.domain.{ CoreError, ExceptionError, PredicateError }

import scala.compat.java8.FutureConverters._
import scala.concurrent.{ ExecutionContext, Future }

object StepFunctions {

  /**
    * The ARN Step Functions assigns to the execution named `executionName` of
    * the standard state machine `stateMachineArn`. Execution names are unique
    * per state machine, so the ARN is known before the execution is started:
    *
    *   arn:<partition>:states:<region>:<account>:stateMachine:<machine>
    *   arn:<partition>:states:<region>:<account>:execution:<machine>:<name>
    */
  def executionArn(
    stateMachineArn: String,
    executionName: String
  ): Either[CoreError, String] =
    stateMachineArn.split(":", -1) match {
      case Array(
          "arn",
          partition,
          "states",
          region,
          account,
          "stateMachine",
          machine
          ) if machine.nonEmpty =>
        Right(
          s"arn:$partition:states:$region:$account:execution:$machine:$executionName"
        )
      case _ =>
        Left(
          PredicateError(
            s"'$stateMachineArn' is not a Step Functions state machine ARN"
          )
        )
    }
}

trait StepFunctionsClient {

  def startExecution(
    stateMachineArn: String,
    executionName: String,
    input: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, StartExecutionResponse]
}

/**
  * NOTE: this client uses v2 of the AWS SDK.
  */
class StepFunctions(val client: SfnAsyncClient) extends StepFunctionsClient {

  override def startExecution(
    stateMachineArn: String,
    executionName: String,
    input: String
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, StartExecutionResponse] = {
    val request = StartExecutionRequest
      .builder()
      .stateMachineArn(stateMachineArn)
      .name(executionName)
      .input(input)
      .build()

    client
      .startExecution(request)
      .toScala
      .toEitherT[CoreError] {
        case e: Exception => ExceptionError(e)
      }
  }
}
