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

import com.pennsieve.utilities.Container
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.regions.Regions
import com.amazonaws.services.ecs.{
  AmazonECSAsync,
  AmazonECSAsyncClientBuilder
}
import com.pennsieve.aws.LocalAWSCredentialsProvider
import net.ceedubs.ficus.Ficus._

trait ECSContainer { self: Container =>

  val ecs_host: String = config.as[String]("ecs.host")
  val ecs_region: Regions = config.as[Option[String]]("ecs.region") match {
    case Some(region) => Regions.fromName(region)
    case None => Regions.US_EAST_1
  }

  val ecs: ECSTrait

}

trait AWSECSContainer extends ECSContainer { self: Container =>

  private lazy val ecsClient: AmazonECSAsync =
    AmazonECSAsyncClientBuilder
      .standard()
      .withCredentials(DefaultAWSCredentialsProviderChain.getInstance())
      .withRegion(ecs_region)
      .build()

  override lazy val ecs: AWSECS = new AWSECS(ecsClient)

}

trait LocalECSContainer extends ECSContainer { self: Container =>

  private lazy val ecsClient: AmazonECSAsync =
    AmazonECSAsyncClientBuilder
      .standard()
      .withCredentials(LocalAWSCredentialsProvider.credentialsProviderChain)
      .withEndpointConfiguration(
        new EndpointConfiguration(ecs_host, ecs_region.getName())
      )
      .build()

  override lazy val ecs: AWSECS = new AWSECS(ecsClient)

}
