/**
  * *   Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.
  */
package com.blackfynn.aws.email

import com.amazonaws.regions.Regions
import com.amazonaws.services.simpleemail.{
  AmazonSimpleEmailService,
  AmazonSimpleEmailServiceClientBuilder
}
import com.blackfynn.utilities.Container
import net.ceedubs.ficus.Ficus._

trait EmailContainer { self: Container =>

  def emailer: Emailer
}

trait AWSEmailContainer extends EmailContainer { self: Container =>

  lazy val client: AmazonSimpleEmailService =
    AmazonSimpleEmailServiceClientBuilder
      .standard()
      .withRegion(Regions.US_EAST_1)
      .build()

  override lazy val emailer: Emailer = new SESEmailer(client)
}

trait LocalEmailContainer extends EmailContainer { self: Container =>
  override lazy val emailer: Emailer = new LoggingEmailer
}
