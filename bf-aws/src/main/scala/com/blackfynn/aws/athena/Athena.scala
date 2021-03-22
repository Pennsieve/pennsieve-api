// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.aws.athena

import com.amazonaws.services.athena.AmazonAthenaAsync
import com.amazonaws.services.athena.model._

import com.blackfynn.aws.AsyncHandler

import scala.concurrent.Future

trait AthenaClient {
  def batchGetNamedQuery(
    request: BatchGetNamedQueryRequest
  ): Future[BatchGetNamedQueryResult]

  def batchGetQueryExecution(
    request: BatchGetQueryExecutionRequest
  ): Future[BatchGetQueryExecutionResult]

  def createNamedQuery(
    request: CreateNamedQueryRequest
  ): Future[CreateNamedQueryResult]

  def getNamedQuery(request: GetNamedQueryRequest): Future[GetNamedQueryResult]

  def deleteNamedQuery(
    request: DeleteNamedQueryRequest
  ): Future[DeleteNamedQueryResult]

  def getQueryExecution(
    request: GetQueryExecutionRequest
  ): Future[GetQueryExecutionResult]

  def getQueryResults(
    request: GetQueryResultsRequest
  ): Future[GetQueryResultsResult]

  def listNamedQueries(
    request: ListNamedQueriesRequest
  ): Future[ListNamedQueriesResult]

  def listQueryExecutions(
    request: ListQueryExecutionsRequest
  ): Future[ListQueryExecutionsResult]

  def startQueryExecution(
    request: StartQueryExecutionRequest
  ): Future[StartQueryExecutionResult]

  def stopQueryExecution(
    request: StopQueryExecutionRequest
  ): Future[StopQueryExecutionResult]

}

class AmazonAthenaClient(val client: AmazonAthenaAsync) extends AthenaClient {

  def batchGetNamedQuery(
    request: BatchGetNamedQueryRequest
  ): Future[BatchGetNamedQueryResult] = {
    val handler =
      new AsyncHandler[BatchGetNamedQueryRequest, BatchGetNamedQueryResult]

    client.batchGetNamedQueryAsync(request, handler)

    handler.promise.future
  }

  def batchGetQueryExecution(
    request: BatchGetQueryExecutionRequest
  ): Future[BatchGetQueryExecutionResult] = {
    val handler = new AsyncHandler[
      BatchGetQueryExecutionRequest,
      BatchGetQueryExecutionResult
    ]

    client.batchGetQueryExecutionAsync(request, handler)

    handler.promise.future
  }

  def createNamedQuery(
    request: CreateNamedQueryRequest
  ): Future[CreateNamedQueryResult] = {
    val handler =
      new AsyncHandler[CreateNamedQueryRequest, CreateNamedQueryResult]

    client.createNamedQueryAsync(request, handler)

    handler.promise.future
  }

  def getNamedQuery(
    request: GetNamedQueryRequest
  ): Future[GetNamedQueryResult] = {
    val handler = new AsyncHandler[GetNamedQueryRequest, GetNamedQueryResult]

    client.getNamedQueryAsync(request, handler)

    handler.promise.future
  }

  def deleteNamedQuery(
    request: DeleteNamedQueryRequest
  ): Future[DeleteNamedQueryResult] = {
    val handler =
      new AsyncHandler[DeleteNamedQueryRequest, DeleteNamedQueryResult]

    client.deleteNamedQueryAsync(request, handler)

    handler.promise.future
  }

  def getQueryExecution(
    request: GetQueryExecutionRequest
  ): Future[GetQueryExecutionResult] = {
    val handler =
      new AsyncHandler[GetQueryExecutionRequest, GetQueryExecutionResult]

    client.getQueryExecutionAsync(request, handler)

    handler.promise.future
  }

  def getQueryResults(
    request: GetQueryResultsRequest
  ): Future[GetQueryResultsResult] = {
    val handler =
      new AsyncHandler[GetQueryResultsRequest, GetQueryResultsResult]

    client.getQueryResultsAsync(request, handler)

    handler.promise.future
  }

  def listNamedQueries(
    request: ListNamedQueriesRequest
  ): Future[ListNamedQueriesResult] = {
    val handler =
      new AsyncHandler[ListNamedQueriesRequest, ListNamedQueriesResult]

    client.listNamedQueriesAsync(request, handler)

    handler.promise.future
  }

  def listQueryExecutions(
    request: ListQueryExecutionsRequest
  ): Future[ListQueryExecutionsResult] = {
    val handler =
      new AsyncHandler[ListQueryExecutionsRequest, ListQueryExecutionsResult]

    client.listQueryExecutionsAsync(request, handler)

    handler.promise.future
  }

  def startQueryExecution(
    request: StartQueryExecutionRequest
  ): Future[StartQueryExecutionResult] = {
    val handler =
      new AsyncHandler[StartQueryExecutionRequest, StartQueryExecutionResult]

    client.startQueryExecutionAsync(request, handler)

    handler.promise.future
  }

  def stopQueryExecution(
    request: StopQueryExecutionRequest
  ): Future[StopQueryExecutionResult] = {
    val handler =
      new AsyncHandler[StopQueryExecutionRequest, StopQueryExecutionResult]

    client.stopQueryExecutionAsync(request, handler)

    handler.promise.future
  }
}
