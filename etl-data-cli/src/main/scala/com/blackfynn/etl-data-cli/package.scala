// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.etl.`data-cli`

import com.blackfynn.core.utilities.DatabaseContainer
import com.blackfynn.utilities.{ Container => ConfigContainer }

import com.typesafe.config.Config
import net.ceedubs.ficus.Ficus._

package object container {

  class DataCLIContainer(val config: Config) extends ConfigContainer {
    val environment: String = config.as[String]("environment")
  }
  type Container = DataCLIContainer with DatabaseContainer
}

package object exceptions {
  class ScoptParsingFailure extends Exception
}
