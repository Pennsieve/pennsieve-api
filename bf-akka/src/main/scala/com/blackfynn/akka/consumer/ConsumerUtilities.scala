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

package com.pennsieve.akka.consumer

import akka.Done
import akka.actor.ActorSystem
import akka.stream.UniqueKillSwitch
import com.pennsieve.service.utilities.ContextLogger
import com.typesafe.scalalogging.Logger

import scala.concurrent.{ ExecutionContext, Future }
import scala.sys.ShutdownHookThread
import scala.util.{ Failure, Success, Try }

object ConsumerUtilities {

  def shutdown(
    actorSystem: ActorSystem
  )(implicit
    logger: ContextLogger,
    executionContext: ExecutionContext
  ): Unit = {
    actorSystem.terminate().onComplete {
      case Success(_) => sys.exit()
      case Failure(exception) =>
        logger.noContext
          .error("Error while shutting down actor system", exception)
        sys.exit(1)
    }
  }

  def handleFutureCompletion[T](
    implicit
    logger: ContextLogger,
    actorSystem: ActorSystem,
    executionContext: ExecutionContext
  ): Try[T] => Unit = {
    case Success(value) =>
      logger.noContext.error("Processing stopped", value)
      shutdown(actorSystem)
    case Failure(exception) =>
      logger.noContext.error("Processing failed", exception)
      shutdown(actorSystem)
  }

  def handle(
    completionFuture: Future[Done]
  )(implicit
    logger: ContextLogger,
    actorSystem: ActorSystem,
    executionContext: ExecutionContext
  ): Unit = {
    completionFuture.onComplete(handleFutureCompletion)
  }

  def addShutdownHook(killSwitch: UniqueKillSwitch): ShutdownHookThread =
    sys.addShutdownHook {
      killSwitch.shutdown()
    }
}
