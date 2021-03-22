// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.aws.ssm

import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagementAsync
import com.amazonaws.services.simplesystemsmanagement.model.{
  GetParametersRequest,
  GetParametersResult
}

import com.blackfynn.aws.AsyncHandler
import cats.implicits._
import collection.JavaConverters._
import com.typesafe.config.{ Config, ConfigFactory }
import scala.concurrent.{ ExecutionContext, Future }

trait SimpleSystemsManagementTrait {
  def getParameters(
    parameters: Set[String],
    withDecryption: Boolean
  )(implicit
    executionContext: ExecutionContext
  ): Future[Map[String, String]]

  def getParametersAsConfig(
    ssmConfigMap: Map[String, String],
    withDecryption: Boolean
  )(implicit
    executionContext: ExecutionContext
  ): Future[Config]
}

class AWSSimpleSystemsManagement(val client: AWSSimpleSystemsManagementAsync)
    extends SimpleSystemsManagementTrait {

  def getParameters(
    parameters: Set[String],
    withDecryption: Boolean
  )(implicit
    executionContext: ExecutionContext
  ): Future[Map[String, String]] = {

    // SSM only supports getting 10 parameters at a time
    parameters
      .grouped(10)
      .toList
      .traverse { tenParameters =>
        val handler =
          new AsyncHandler[GetParametersRequest, GetParametersResult]

        val request = new GetParametersRequest()
          .withNames(tenParameters.asJava)
          .withWithDecryption(withDecryption)

        client.getParametersAsync(request, handler)
        handler.promise.future.flatMap { result =>
          result.getInvalidParameters.asScala.toList match {
            case Nil =>
              Future.successful(result.getParameters.asScala.toList.map {
                parameter =>
                  parameter.getName -> parameter.getValue
              }.toMap)

            case p => Future.failed(InvalidParameters(p))
          }
        }
      }
      .map(_.flatten.toMap)
  }

  /*
   * Takes a map of SSM parameters to Typesafe config keys and generates a
   *   Typesafe config.
   *
   * ssmConfigMap arguement should have the form SSM parameter -> Typesafe config path,
   * for example: "dev-pennsieve-postgres-user" -> "postgres.user"
   *
   * Returns a Typesafe config, which can be merged with other Typesafe configs
   * using config.withFallback(secondConfig)
   *
   */
  def getParametersAsConfig(
    ssmConfigMap: Map[String, String],
    withDecryption: Boolean
  )(implicit
    executionContext: ExecutionContext
  ): Future[Config] = {
    val parameters = ssmConfigMap.keySet
    val values = getParameters(parameters, withDecryption)

    val configMap: Future[Map[String, String]] =
      values
        .map(_.toList)
        .flatMap { results =>
          results.traverse {
            case (parameter, value) =>
              ssmConfigMap.get(parameter) match {
                case Some(k) => Future.successful((k, value))
                case None => Future.failed(InvalidParameterResponse(parameter))
              }
          }
        }
        .map(_.toMap)

    configMap
      .map(_.asJava)
      .map(ConfigFactory.parseMap)
  }

}
