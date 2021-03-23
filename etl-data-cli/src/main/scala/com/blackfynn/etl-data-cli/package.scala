// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.etl.`data-cli`

import com.pennsieve.core.utilities.DatabaseContainer
import com.pennsieve.utilities.{ Container => ConfigContainer }

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
