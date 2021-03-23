// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

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
