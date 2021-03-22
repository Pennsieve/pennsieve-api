package com.blackfynn.clients

import akka.util.ByteString
import akka.http.scaladsl.HttpExt
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{ Authorization, OAuth2BearerToken }
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import cats.data.EitherT
import cats.implicits._
import com.blackfynn.core.utilities.FutureEitherHelpers.implicits._
import com.blackfynn.domain.{ CoreError, ExceptionError }
import io.circe.syntax._
import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.parser.decode

import java.net.URL
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

trait UrlShortenerClient {
  def shortenUrl(longUrl: URL): Future[URL]
}

/**
  * URL shortener using Bitly. Configured with a "Generic Access Token" from the
  * Bitly web application.
  *
  * See https://dev.bitly.com/v4_documentation.html for full documentation
  */
class BitlyUrlShortenerClient(
  httpClient: HttpExt,
  accessToken: String
)(implicit
  executionContext: ExecutionContext,
  materializer: Materializer
) extends UrlShortenerClient {

  def shortenUrl(longUrl: URL): Future[URL] = {
    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = "https://api-ssl.bitly.com/v4/bitlinks",
      entity = HttpEntity.Strict(
        ContentType(MediaTypes.`application/json`),
        ByteString(CreateBitlinkRequest(longUrl).asJson.noSpaces)
      ),
      headers = List(Authorization(OAuth2BearerToken(accessToken)))
    )

    for {
      response <- httpClient.singleRequest(request)
      shortUrl <- response match {
        case HttpResponse(StatusCodes.OK | StatusCodes.Created, _, entity, _) =>
          Unmarshal(entity)
            .to[String]
            .map(decode[CreateBitlinkResponse](_))
            .flatMap(_.fold(Future.failed, Future.successful))
            .map(_.link)
        case error =>
          Unmarshal(error.entity)
            .to[String]
            .flatMap(msg => Future.failed(new Exception(msg)))
      }
    } yield shortUrl
  }
}

case class CreateBitlinkRequest(longUrl: URL)

object CreateBitlinkRequest {
  // Custom encoder to support snake_case fields.
  implicit val encodeUser: Encoder[CreateBitlinkRequest] =
    Encoder.forProduct1("long_url")(_.longUrl.toString)
}

case class CreateBitlinkResponse(link: URL)

object CreateBitlinkResponse {
  implicit val urlDecoder: Decoder[URL] = Decoder.decodeString.emap { str =>
    Either.catchNonFatal(new URL(str)).leftMap(_ => "URL")
  }

  implicit val decoder: Decoder[CreateBitlinkResponse] =
    deriveDecoder[CreateBitlinkResponse]
}
