/*
 * Copyright 2021 University of Pennsylvania
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Copyright (c) [2018] - [2021] Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.models

import io.circe.{ Decoder, Encoder }

import java.util.UUID

import CognitoId._

sealed trait CognitoId {
  val value: UUID
  override def toString: String = value.toString

  def asUserPoolId: Either[Throwable, CognitoId.UserPoolId]
  def asTokenPoolId: Either[Throwable, CognitoId.TokenPoolId]
}

object CognitoId {

  final case class UserPoolId(value: UUID) extends CognitoId {
    def asUserPoolId = Right(this)
    def asTokenPoolId = Left(InvalidCognitoId("token", "user"))
  }

  final case class TokenPoolId(value: UUID) extends CognitoId {
    def asUserPoolId = Left(InvalidCognitoId("user", "token"))
    def asTokenPoolId = Right(this)
  }

  case class InvalidCognitoId(expected: String, got: String) extends Throwable {
    override def getMessage: String =
      s"Expected ${expected} pool Cognito ID but got ${got} pool ID"
  }

  object UserPoolId {
    implicit val cognitoIdEncoder: Encoder[UserPoolId] =
      Encoder[UUID].contramap(_.value)

    implicit val cognitoIdDecoder: Decoder[UserPoolId] =
      Decoder.decodeUUID.map(CognitoId.UserPoolId(_))

    def randomId() = CognitoId.UserPoolId(UUID.randomUUID())
  }

  object TokenPoolId {
    implicit val cognitoIdEncoder: Encoder[TokenPoolId] =
      Encoder[UUID].contramap(_.value)

    implicit val cognitoIdDecoder: Decoder[TokenPoolId] =
      Decoder.decodeUUID.map(CognitoId.TokenPoolId(_))

    def randomId() = CognitoId.TokenPoolId(UUID.randomUUID())
  }
}
