package com.pennsieve.aws.cognito

import com.pennsieve.models.CognitoId
import java.util.UUID
import scala.collection.mutable
import scala.concurrent.{ ExecutionContext, Future }

class MockCognito() extends CognitoClient {

  val sentInvites: mutable.ArrayBuffer[String] =
    mutable.ArrayBuffer.empty

  val reSentInvites: mutable.Map[String, CognitoId] =
    mutable.Map.empty

  def adminCreateUser(
    email: String
  )(implicit
    ec: ExecutionContext
  ): Future[CognitoId] = {
    sentInvites.append(email)
    Future.successful(CognitoId.randomId())
  }

  def resendUserInvite(
    email: String,
    cognitoId: CognitoId
  )(implicit
    ec: ExecutionContext
  ): Future[CognitoId] = {
    reSentInvites.update(email, cognitoId)
    Future.successful(cognitoId)
  }

  def reset(): Unit = {
    sentInvites.clear()
    reSentInvites.clear()
  }
}
