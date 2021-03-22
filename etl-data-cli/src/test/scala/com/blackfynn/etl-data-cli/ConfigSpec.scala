// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.etl.`data-cli`

import com.blackfynn.aws.ssm.{
  AWSSimpleSystemsManagementContainer,
  LocalSimpleSystemsManagerContainer
}
import com.blackfynn.utilities.{ Container => ConfigContainer }
import com.typesafe.config.ConfigFactory
import net.ceedubs.ficus.Ficus._
import org.scalatest.{ FlatSpec, Matchers }

class ConfigSpec extends FlatSpec with Matchers {

  "this" should "create an ssm container" in {

    val baseConfig = ConfigFactory.load()

    val environment: String = baseConfig.as[String]("environment")
    val isLocal: Boolean = environment.toLowerCase == "local"

    val ssmContainer =
      if (isLocal) {
        new { val config = baseConfig } with ConfigContainer
        with LocalSimpleSystemsManagerContainer
      } else {
        new { val config = baseConfig } with ConfigContainer
        with AWSSimpleSystemsManagementContainer
      }

  }
}
