package com.pennsieve.aws

import com.amazonaws.auth.{
  AWSCredentialsProviderChain,
  AWSStaticCredentialsProvider,
  BasicAWSCredentials
}

object LocalAWSCredentialsProvider {
  private val credentials =
    new BasicAWSCredentials("access-key", "secret-key")

  private val staticCredentialsProvider = new AWSStaticCredentialsProvider(
    credentials
  )

  val credentialsProviderChain: AWSCredentialsProviderChain =
    new AWSCredentialsProviderChain(staticCredentialsProvider)
}
