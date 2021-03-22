package com.blackfynn.client

import com.blackfynn.clients.ToBearer
import com.blackfynn.notifications.MessageType.Mention
import com.blackfynn.notifications.NotificationMessage
import com.typesafe.scalalogging.LazyLogging
import io.circe.syntax._
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.{ CloseableHttpClient, HttpClients }

class NotificationServiceClient(host: String, port: Int) extends LazyLogging {

  def notify[B](
    notification: NotificationMessage,
    token: B
  )(implicit
    b: ToBearer[B]
  ): Unit = {
    val httpClient: CloseableHttpClient = HttpClients.createMinimal()
    val destination = notification.messageType match {
      case Mention => "sendMention"
      case _ => "send"
    }
    val url = s"$host:$port/notification/$destination"
    val post = new HttpPost(url)
    val se = new StringEntity(notification.asJson.noSpaces)
    se.setContentType("application/json")
    post.setEntity(se)
    post.setHeader("Authorization", s"Bearer ${token}")
    val response = httpClient.execute(post)

    val status = response.getStatusLine.getStatusCode
    if (status != 200) {
      logger.error(s"bad response from posting to $url")
      logger.error(s"status code: $status")
      val entity = response.getEntity.getContent
      val responseString = scala.io.Source.fromInputStream(entity).mkString
      logger.error(s"response is : $responseString")
    }
    httpClient.close()
  }

}
