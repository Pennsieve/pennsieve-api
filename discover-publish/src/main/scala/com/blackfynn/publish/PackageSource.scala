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

package com.pennsieve.publish

import akka.NotUsed
import akka.stream.scaladsl.{ Source }

import cats.data._
import cats.implicits._

import com.pennsieve.models.{ Package }
import com.pennsieve.publish.models.{ PackagePath }

import scala.collection.mutable.{ ArrayStack }
import scala.concurrent.{ ExecutionContext, Future }

/**
  * Source that emits all the packages in a dataset.
  *
  * Maintains an internal stack containing the children of packages that have
  * already been sent.
  */
object PackagesSource {

  def apply(
  )(implicit
    container: PublishContainer,
    ec: ExecutionContext
  ): Source[PackagePath, NotUsed] =
    // Initialize the state for unfolding using the packages at the top level
    // of the dataset.
    Source
      .future(
        container.packageManager
          .children(None, container.dataset)
          .value
          .flatMap {
            case Left(e) => Future.failed(e)
            case Right(children) => Future.successful(children)
          }
      )
      // Pass the top level packages into a substream that traverses the
      // package tree by maintaining a stack of packages to be emitted
      .flatMapConcat(
        children =>
          Source
            .unfoldAsync(UnfoldState(children))(getNextPackage)
      )

  /**
    * Return the next package to emit in the stream, adding the children of
    * the package to the stack.
    */
  def getNextPackage(
    state: UnfoldState
  )(implicit
    container: PublishContainer,
    ec: ExecutionContext
  ): Future[Option[(UnfoldState, PackagePath)]] =
    if (state.stack.isEmpty)
      Future.successful(None) // Done. Shutdown the stream
    else {
      val (parent, path) = state.stack.pop
      container.packageManager
        .children(Some(parent), container.dataset)
        .map(
          children =>
            state.copy(
              stack = state.stack ++ children
                .map(pkg => (pkg, path :+ parent.name))
            )
        )
        .value
        .flatMap {
          case Left(e) => Future.failed(e)
          case Right(state) => Future.successful(Some((state, (parent, path))))
        }
    }

  /**
    * Accumulator state for unfold traversal
    */
  case class UnfoldState(stack: ArrayStack[PackagePath])

  object UnfoldState {
    def apply(packages: List[Package]): UnfoldState =
      UnfoldState(new ArrayStack() ++ packages.map(pkg => (pkg, Seq[String]())))
  }
}
