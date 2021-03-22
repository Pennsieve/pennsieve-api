package com.blackfynn.helpers

import com.blackfynn.audit.middleware.{ Auditor, ToMessage, TraceId }

import scala.concurrent.Future

class MockAuditLogger extends Auditor {
  override def enhance[T](
    traceId: TraceId,
    payload: T
  )(implicit
    converter: ToMessage[T]
  ): Future[Unit] = {
    Future.successful(())
  }
}
