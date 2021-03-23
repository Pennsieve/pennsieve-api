// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.aws.lambda

import com.amazonaws.services.lambda.AWSLambdaAsync
import com.amazonaws.services.lambda.model.{ InvokeRequest, InvokeResult }

import com.pennsieve.aws.AsyncHandler

import scala.concurrent.Future

trait LambdaClient {
  def invoke(request: InvokeRequest): Future[InvokeResult]
}

class AWSLambdaClient(val client: AWSLambdaAsync) extends LambdaClient {

  def invoke(request: InvokeRequest): Future[InvokeResult] = {
    val handler = new AsyncHandler[InvokeRequest, InvokeResult]

    client.invokeAsync(request, handler)

    handler.promise.future
  }

}
