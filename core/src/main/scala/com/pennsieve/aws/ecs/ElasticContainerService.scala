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

package com.pennsieve.aws.ecs

import com.pennsieve.aws.AsyncHandler
import com.amazonaws.services.ecs.AmazonECSAsync
import com.amazonaws.services.ecs.model.{
  ListTasksRequest,
  ListTasksResult,
  RunTaskRequest,
  RunTaskResult
}

import scala.concurrent.Future

trait ECSTrait {
  def runTaskAsync(task: RunTaskRequest): Future[RunTaskResult]
  def listTasksAsync(task: ListTasksRequest): Future[ListTasksResult]
}

class AWSECS(val client: AmazonECSAsync) extends ECSTrait {

  def runTaskAsync(request: RunTaskRequest): Future[RunTaskResult] = {
    val handler = new AsyncHandler[RunTaskRequest, RunTaskResult]

    client.runTaskAsync(request, handler)

    handler.promise.future
  }

  def listTasksAsync(request: ListTasksRequest): Future[ListTasksResult] = {
    val handler = new AsyncHandler[ListTasksRequest, ListTasksResult]

    client.listTasksAsync(request, handler)

    handler.promise.future
  }
}
