// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.uploads.consumer.antivirus

import com.blackfynn.utilities.{ Container => ConfigContainer }
import net.ceedubs.ficus.Ficus._

trait ClamAVContainer { self: ConfigContainer =>

  val clamd_host: String = config.as[String]("clamd.host")
  val clamd_port: Int = config.as[Int]("clamd.port")

  val clamd: ClamD = new ClamD(clamd_host, clamd_port)
}
