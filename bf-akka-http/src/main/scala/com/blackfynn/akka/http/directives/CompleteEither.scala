package com.pennsieve.akka.http.directives

import akka.http.scaladsl.marshalling.{
  ToEntityMarshaller,
  ToResponseMarshaller
}
import akka.http.scaladsl.model.{ StatusCode, StatusCodes }
import akka.http.scaladsl.server.Directives.{ complete, onComplete }
import akka.http.scaladsl.server.{ Directive, Route, StandardRoute }
import akka.stream.scaladsl.Source

import scala.concurrent.Future
import scala.util.{ Failure, Success, Try }

object CompleteEither {

  def completeEither[R, A](
    either: Either[(StatusCode, String), R],
    successStatusCode: StatusCodes.Success = StatusCodes.OK
  )(implicit
    marshaller: ToEntityMarshaller[R]
  ): StandardRoute =
    either match {
      case Right(value) =>
        complete(successStatusCode -> value)
      case Left(err) =>
        complete(err)
    }

  def completeEitherSource[R, A, N](
    eitherSource: Either[(StatusCode, String), Source[R, N]]
  )(implicit
    marshaller: ToResponseMarshaller[Source[R, N]]
  ): StandardRoute =
    eitherSource match {
      case Right(source) =>
        complete(source)
      case Left(err) =>
        complete(err)
    }

  def completeFutureEither[R, A](
    futureEither: Future[Either[(StatusCode, String), R]]
  )(implicit
    marshaller: ToResponseMarshaller[R]
  ): Route = {
    onComplete(futureEither) {
      case Success(v) =>
        v match {
          case Right(s) => complete(s)
          case Left(err) => complete(Future.successful(err))
        }
      case Failure(err) => complete(StatusCodes.InternalServerError -> err)
    }
  }

  def completeFutureEitherStatus[R, A](
    successStatusCode: StatusCodes.Success,
    futureEither: Future[Either[(StatusCode, String), R]]
  )(implicit
    marshaller: ToEntityMarshaller[R]
  ): Route =
    onComplete(futureEither) {
      case Success(v) =>
        v match {
          case Right(s) => complete(successStatusCode -> s)
          case Left(err) => complete(Future.successful(err))
        }
      case Failure(err) => complete(StatusCodes.InternalServerError -> err)
    }

  def completeEitherFuture[R, A](
    eitherFuture: Either[(StatusCode, String), Future[R]]
  )(implicit
    marshaller: ToResponseMarshaller[R]
  ): Route =
    eitherFuture match {
      case Right(future) =>
        onComplete(future) {
          case Success(value) =>
            complete(value)
          case Failure(err) =>
            complete(StatusCodes.InternalServerError -> err)
        }
      case Left(err) =>
        complete(Future.successful(err))
    }
}
