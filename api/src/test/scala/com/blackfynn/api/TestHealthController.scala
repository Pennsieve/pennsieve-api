package com.pennsieve.api

class TestHealthController extends BaseApiTest {

  override def afterStart(): Unit = {
    super.afterStart()

    addServlet(
      new HealthController(insecureContainer, system.dispatcher)(swagger),
      "/*"
    )
  }

  test("get health") {
    get(s"/", headers = traceIdHeader()) {
      status should equal(200)
    }
  }

}
