package com.blackfynn.helpers

import com.blackfynn.clients.UrlShortenerClient

import java.net.URL
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future

class MockUrlShortenerClient extends UrlShortenerClient {
  val shortenedUrls: ArrayBuffer[URL] = ArrayBuffer.empty[URL]
  def shortenUrl(longUrl: URL): Future[URL] = {
    shortenedUrls += longUrl
    Future.successful(new URL("https://bit.ly/short"))
  }
}
