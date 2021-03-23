package com.pennsieve.aws.cognito

import com.pennsieve.utilities.Container
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderAsyncClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import com.pennsieve.aws.LocalAWSCredentialsProviderV2
import net.ceedubs.ficus.Ficus._

trait CognitoContainer { self: Container =>

  val cognitoClient: CognitoClient
}

trait AWSCognitoContainer extends CognitoContainer { self: Container =>

  lazy val cognitoClient: CognitoClient = Cognito.fromConfig(config)
}

trait LocalCognitoContainer extends CognitoContainer { self: Container =>

  // TODO: override with local client
  lazy val cognitoClient: CognitoClient = ???

}
