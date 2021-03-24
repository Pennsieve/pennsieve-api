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

package com.pennsieve.akka.http

import org.scalactic.source
import org.scalatest.exceptions.{ StackDepthException, TestFailedException }

object EitherValue {

  implicit class RightValuable[L, R](
    either: Either[L, R]
  )(implicit
    pos: source.Position
  ) {

    /**
      * Returns the <code>Right</code> value contained in the wrapped <code>RightProjection</code>, if defined as a <code>Right</code>, else throws <code>TestFailedException</code> with
      * a detail message indicating the <code>Either</code> was defined as a <code>Right</code>, not a <code>Left</code>.
      */
    def value: R =
      try {
        either.right.get
      } catch {
        case cause: NoSuchElementException =>
          throw new TestFailedException(
            messageFun = (_: StackDepthException) =>
              Some(
                s"The Either on which value was invoked was not defined as a Right. Actually: ${either.left.get}"
              ),
            cause = Some(cause),
            pos = pos
          )
      }
  }

}
