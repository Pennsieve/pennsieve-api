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

import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sfn.SfnAsyncClient

import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import com.pennsieve.aws.LocalAWSCredentialsProviderV2
import com.pennsieve.utilities.Container
import net.ceedubs.ficus.Ficus._

import java.net.URI

trait StepFunctionsContainer { self: Container =>

  val stepFunctions: StepFunctionsClient

  val stepfunctions_host: String = config.as[String]("stepfunctions.host")
  val stepfunctions_region: Region =
    Region.of(config.as[String]("stepfunctions.region"))
}

trait AWSStepFunctionsContainer extends StepFunctionsContainer {
  self: Container =>

  override lazy val stepFunctions: StepFunctionsClient = new StepFunctions(
    SfnAsyncClient
      .builder()
      .region(stepfunctions_region)
      .httpClientBuilder(NettyNioAsyncHttpClient.builder())
      .build()
  )

}

trait LocalStepFunctionsContainer extends StepFunctionsContainer {
  self: Container =>

  override lazy val stepFunctions: StepFunctionsClient = new StepFunctions(
    SfnAsyncClient
      .builder()
      .region(stepfunctions_region)
      .credentialsProvider(LocalAWSCredentialsProviderV2.credentialsProvider)
      .endpointOverride(new URI(stepfunctions_host))
      .httpClientBuilder(NettyNioAsyncHttpClient.builder())
      .build()
  )
}
