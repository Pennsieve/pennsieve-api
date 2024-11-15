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

package com.pennsieve.aws.cognito
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderAsyncClient
import software.amazon.awssdk.services.cognitoidentityprovider.model.{
  AdminCreateUserRequest,
  AdminCreateUserResponse
}

import java.util.concurrent.CompletableFuture

class UnsetMockResponseException(message: String) extends Exception(message) {}

// A mock CognitoIdentityProviderAsyncClient. The trait has default implementations that throw UnsupportedOperationExceptions, so we only
// need to implement methods actually used in tests.
class MockCognitoIdentityProviderAsyncClient
    extends CognitoIdentityProviderAsyncClient {

  var adminCreateUserResponse: Either[AdminCreateUserResponse, Throwable] =
    Right(new UnsetMockResponseException("adminCreateUser"))

  override def close(): Unit = {}

  override def serviceName(): String =
    CognitoIdentityProviderAsyncClient.SERVICE_NAME

  override def adminCreateUser(
    adminCreateUserRequest: AdminCreateUserRequest
  ): CompletableFuture[AdminCreateUserResponse] = {
    CompletableFuture.supplyAsync(() => {
      adminCreateUserResponse match {
        case Left(value) => value
        case Right(throwable) => throw throwable
      }
    })
  }

  def reset(): Unit = {
    adminCreateUserResponse = Right(
      new UnsetMockResponseException("adminCreateUser")
    )
  }

}
