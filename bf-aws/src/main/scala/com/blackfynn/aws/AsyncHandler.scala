package com.blackfynn.aws

import com.amazonaws.AmazonWebServiceRequest
import com.amazonaws.handlers.{ AsyncHandler => AWSAsyncHandler }

import scala.concurrent.Promise

class AsyncHandler[Request <: AmazonWebServiceRequest, Result]
    extends AWSAsyncHandler[Request, Result] {

  val promise: Promise[Result] = Promise[Result]

  override def onError(exception: Exception): Unit = {
    promise.failure(exception)
  }

  override def onSuccess(request: Request, result: Result): Unit = {
    promise.success(result)
  }

}
