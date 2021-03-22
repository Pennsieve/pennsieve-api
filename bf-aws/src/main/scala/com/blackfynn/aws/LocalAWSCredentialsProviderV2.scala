package com.blackfynn.aws

import software.amazon.awssdk.auth.credentials.{
  AwsBasicCredentials,
  AwsCredentialsProvider,
  StaticCredentialsProvider
}

/**
  * Credentials for users of v2 AWS SDK.
  */
object LocalAWSCredentialsProviderV2 {
  private val credentials =
    AwsBasicCredentials.create("access-key", "secret-key")

  val credentialsProvider: AwsCredentialsProvider =
    StaticCredentialsProvider.create(credentials)
}
