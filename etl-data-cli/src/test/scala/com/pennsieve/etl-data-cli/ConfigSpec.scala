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

package com.pennsieve.etl.`data-cli`

import com.pennsieve.aws.ssm.{
  AWSSimpleSystemsManagementContainer,
  LocalSimpleSystemsManagerContainer
}
import com.pennsieve.etl.`data-cli`.ConfigBuilder.AbstractConfigContainer
import com.pennsieve.utilities.{ Container => ConfigContainer }
import com.typesafe.config.ConfigFactory
import net.ceedubs.ficus.Ficus._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ConfigSpec extends AnyFlatSpec with Matchers {

  "this" should "create an ssm container" in {

    val baseConfig = ConfigFactory.load()

    val environment: String = baseConfig.as[String]("environment")
    val isLocal: Boolean = environment.toLowerCase == "local"

    val ssmContainer =
      if (isLocal) {
        new AbstractConfigContainer(baseConfig)
        with LocalSimpleSystemsManagerContainer
      } else {
        new AbstractConfigContainer(baseConfig)
        with AWSSimpleSystemsManagementContainer
      }

  }
}
