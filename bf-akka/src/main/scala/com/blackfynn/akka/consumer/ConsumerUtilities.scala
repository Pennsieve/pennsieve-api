package com.pennsieve.akka.consumer

import akka.Done
import akka.actor.ActorSystem
import akka.stream.{ ActorMaterializerSettings, Supervision, UniqueKillSwitch }
import com.pennsieve.service.utilities.ContextLogger
import com.typesafe.scalalogging.Logger

import scala.concurrent.{ ExecutionContext, Future }
import scala.sys.ShutdownHookThread
import scala.util.{ Failure, Success, Try }

object ConsumerUtilities {

  def actorMaterializerSettings(
    actorSystem: ActorSystem,
    logger: Logger
  ): ActorMaterializerSettings =
    ActorMaterializerSettings(actorSystem)
      .withSupervisionStrategy { exception: Throwable =>
        logger.error("Unhandled exception thrown", exception)

        Supervision.resume
      }

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
