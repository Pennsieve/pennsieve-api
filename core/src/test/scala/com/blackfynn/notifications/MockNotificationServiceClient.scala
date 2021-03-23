package com.pennsieve.notifications

import com.pennsieve.client.NotificationServiceClient
import com.pennsieve.clients.ToBearer

class MockNotificationServiceClient
    extends NotificationServiceClient("mock-host", 0) {

  override def notify[B](
    notification: NotificationMessage,
    token: B
  )(implicit
    b: ToBearer[B]
  ): Unit = ()
}
