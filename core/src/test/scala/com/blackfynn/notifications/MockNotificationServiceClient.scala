package com.blackfynn.notifications

import com.blackfynn.client.NotificationServiceClient
import com.blackfynn.clients.ToBearer

class MockNotificationServiceClient
    extends NotificationServiceClient("mock-host", 0) {

  override def notify[B](
    notification: NotificationMessage,
    token: B
  )(implicit
    b: ToBearer[B]
  ): Unit = ()
}
