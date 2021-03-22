package com.blackfynn.test

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
