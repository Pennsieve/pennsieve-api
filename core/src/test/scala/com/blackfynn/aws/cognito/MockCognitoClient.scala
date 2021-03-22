package com.blackfynn.aws.cognito

import com.blackfynn.models.CognitoId
import java.util.UUID
import scala.collection.mutable
import scala.concurrent.{ ExecutionContext, Future }

class MockCognito() extends CognitoClient {

  val sentInvites: mutable.ArrayBuffer[String] =
    mutable.ArrayBuffer.empty

  val sentTokenInvites: mutable.ArrayBuffer[String] =
    mutable.ArrayBuffer.empty

  val sentDeletes: mutable.ArrayBuffer[String] =
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

  def adminCreateToken(
    email: String
  )(implicit
    ec: ExecutionContext
  ): Future[CognitoId] = {
    sentTokenInvites.append(email)
    Future.successful(CognitoId.randomId())
  }

  def adminDeleteUser(
    email: String,
    userPoolId: String
  )(implicit
    ec: ExecutionContext
  ): Future[Unit] = {
    sentDeletes.append(email)
    Future.successful(Unit)
  }

  def getTokenPoolId(): String = {
    "__MOCK__tokenPoolId"
  }

  def getUserPoolId(): String = {
    "__MOCK__userPoolId"
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
    sentDeletes.clear()
    sentInvites.clear()
    reSentInvites.clear()
  }
}
