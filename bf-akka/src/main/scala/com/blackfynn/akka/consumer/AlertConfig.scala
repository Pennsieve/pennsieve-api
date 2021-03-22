package com.blackfynn.akka.consumer

import com.blackfynn.utilities.Container
import net.ceedubs.ficus.Ficus._

trait AlertConfig { self: Container =>
  // Alert configuration
  val alertQueue: String = config.as[String]("alert.sqsQueue")
  val alertTopic: String = config.as[String]("alert.snsTopic")
}
