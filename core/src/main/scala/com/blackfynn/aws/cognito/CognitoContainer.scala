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

import com.pennsieve.utilities.Container
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderAsyncClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import com.pennsieve.aws.LocalAWSCredentialsProviderV2
import net.ceedubs.ficus.Ficus._

trait CognitoContainer { self: Container =>

  val cognitoConfig: CognitoConfig
  val cognitoClient: CognitoClient
}

trait AWSCognitoContainer extends CognitoContainer { self: Container =>

  lazy val cognitoConfig: CognitoConfig = CognitoConfig(config)
  lazy val cognitoClient: CognitoClient = Cognito(config)
}

trait LocalCognitoContainer extends CognitoContainer { self: Container =>

  // TODO: override with local client
  lazy val cognitoConfig: CognitoConfig = ???
  lazy val cognitoClient: CognitoClient = ???
}
