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

package com.pennsieve.test

import org.scalatest._
import com.dimafeng.testcontainers._
import org.junit.runner.Description

/**
  * Adapted from `ForAllTestContainers`:`
  * https://github.com/testcontainers/testcontainers-scala/blob/59d332ea0ad0cc809fd029d88faa4f109ecb9f98/test-framework/scalatest/src/main/scala/com/dimafeng/testcontainers/ForAllTestContainer.scala#L10
  *
  * Share Docker containers between test suites. Start Docker containers before
  * tests run, but do not stop the containers when the tests are complete. The
  * `ryuk` sidecar started by `testcontainers` removes the containers a couple
  * seconds after the tests complete.
  *
  * Containers must be cached on the global `DockerContainers` object to ensure
  * only one container is started.
  */
trait PersistantTestContainers extends SuiteMixin {
  self: Suite with StackedDockerContainer =>

  implicit private val suiteDescription =
    Description.createSuiteDescription(self.getClass)

  def container: Container =
    MultipleContainers(stackedContainers.toSeq.map(new LazyContainer(_)): _*)

  abstract override def run(testName: Option[String], args: Args): Status = {
    if (expectedTestCount(args.filter) == 0) {
      new CompositeStatus(Set.empty)
    } else {
      container.start()
      afterStart()
      super.run(testName, args)
    }
  }

  def afterStart(): Unit = {}
}

/**
  * Mixin for a single Docker container.
  *
  * Implementing traits must add their container to the `stackedContainers` list with:
  *
  *   override def stackedContainers = newContainer :: super.stackedContainers
  *
  * If the Docker container should be reused between test runs, cache it on the
  * global `DockerContainers` object.
  */
trait StackedDockerContainer {
  def stackedContainers: List[Container] = List.empty
}
