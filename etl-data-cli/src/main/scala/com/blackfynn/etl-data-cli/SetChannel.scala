// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.etl.`data-cli`

import cats.implicits._
import scala.math.min
import scala.math.max
import com.pennsieve.db.{ ChannelsMapper, OrganizationsMapper }
import com.pennsieve.etl.`data-cli`.container._
import com.pennsieve.etl.`data-cli`.exceptions._
import com.pennsieve.models.{ Channel, ModelProperty, NodeCodes, Organization }
import com.pennsieve.traits.PostgresProfile.api._

import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.parser.decode
import io.circe.syntax._
import io.circe.java8.time._

import java.io.{ FileWriter, File => JavaFile }

import net.ceedubs.ficus.Ficus._

import scala.io.Source
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try

import scopt.OptionParser

object SetChannel {

  case class ChannelInfo(
    name: String,
    start: Long,
    end: Long,
    unit: String,
    rate: Double,
    `type`: String,
    group: Option[String],
    lastAnnotation: Option[Long],
    spikeDuration: Option[Long],
    properties: Option[List[ModelProperty]],
    id: Option[Int] = None
  )
  object ChannelInfo {
    implicit val encoder: Encoder[ChannelInfo] = deriveEncoder[ChannelInfo]
    implicit val decoder: Decoder[ChannelInfo] = deriveDecoder[ChannelInfo]
  }

  def createOrUpdateByNameAndPackageId(
    packageId: Int,
    organizationId: Int,
    data: ChannelInfo
  )(implicit
    container: Container
  ): DBIO[Channel] = {
    for {
      organization <- OrganizationsMapper.getOrganization(organizationId)
      channels = new ChannelsMapper(organization)

      result <- channels
        .getByPackageIdAndName(packageId, data.name.trim)
        .result
        .headOption
        .flatMap {
          case Some(channel)
              if data.`type` == channel.`type` && math.abs(
                1 - (data.rate / channel.rate)
              ) < 0.02 => {
            update(packageId, organizationId, channel.id, data)(container)
          }

          case None => {
            val nodeId = NodeCodes.generateId(NodeCodes.channelCode)
            // these default values are specific and set on purpose
            val properties = data.properties.getOrElse(Nil)
            val lastAnnotation = data.lastAnnotation.getOrElse(0L)

            channels returning channels += Channel(
              nodeId,
              packageId,
              data.name.trim,
              data.start,
              data.end,
              data.unit,
              data.rate,
              data.`type`,
              data.group,
              lastAnnotation,
              data.spikeDuration,
              properties
            )
          }
        }

    } yield result
  }

  def update(
    packageId: Int,
    organizationId: Int,
    channelId: Int,
    data: ChannelInfo
  )(implicit
    container: Container
  ): DBIO[Channel] = {

    for {
      organization <- OrganizationsMapper.getOrganization(organizationId)
      channels = new ChannelsMapper(organization)
      original <- channels.getChannel(channelId)

      updated = original.copy(
        name = data.name,
        start = min(data.start, original.start),
        end = max(data.end, original.end),
        unit = data.unit,
        rate = data.rate,
        `type` = data.`type`,
        lastAnnotation = data.lastAnnotation.getOrElse(original.lastAnnotation),
        group = if (data.group.isDefined) data.group else original.group,
        spikeDuration =
          if (data.spikeDuration.isDefined) data.spikeDuration
          else original.spikeDuration,
        properties = ModelProperty
          .merge(original.properties, data.properties.getOrElse(Nil))
      )

      _ <- channels.get(original.id).update(updated)
    } yield updated
  }

  def set(
    packageId: Int,
    organizationId: Int,
    data: ChannelInfo,
    output: JavaFile
  )(implicit
    container: Container
  ): Future[Channel] = {
    val query: DBIO[Channel] = data.id match {
      case Some(channelId) =>
        update(packageId, organizationId, channelId, data)(container)

      //In the append case, the list of channel is passed to the processor but get resolved after the processing, which
      // can lead to a discrepancy between existing channels in the DB and the ones that were passed (race condition)
      // to prevent it, we check the existence of a similar channel before adding it.
      case None =>
        createOrUpdateByNameAndPackageId(packageId, organizationId, data)(
          container
        )
    }

    for {
      channel <- container.db.run(query.transactionally)
      _ <- Future { FileOutputWriter.writeJson(channel.asJson, output) }
    } yield channel
  }

  // Note: default values required by scopt
  case class CLIConfig(
    channel: JavaFile = new JavaFile("channel.json"),
    output: JavaFile = new JavaFile("channel.json"),
    packageId: Int = 1,
    organizationId: Int = 1
  ) {
    override def toString: String = s"""
    | package-id = $packageId
    | organization-id = $organizationId
    | channel-info = ${channel.toString}
    | output = ${output.toString}
    """
  }

  val parser = new OptionParser[CLIConfig]("") {
    head("set-channel")

    opt[JavaFile]("channel-info")
      .required()
      .valueName("<file>")
      .action((value, config) => config.copy(channel = value))
      .text("channel-info is a required file property")

    opt[JavaFile]('o', "output-file")
      .required()
      .valueName("<file>")
      .action((value, config) => config.copy(output = value))
      .text("output-file is a required file property")

    opt[Int]("package-id")
      .required()
      .valueName("<id>")
      .action((value, config) => config.copy(packageId = value))
      .text("package-id is a required file property")

    opt[Int]("organization-id")
      .required()
      .valueName("<id>")
      .action((value, config) => config.copy(organizationId = value))
      .text("organization-id is a required file property")
  }

  def decodeChannelInfo(file: JavaFile): Future[ChannelInfo] = {
    val parsed = for {
      source <- Try { Source.fromFile(file) }.toEither
      decoded <- decode[ChannelInfo](source.getLines.mkString(""))
      _ <- Try { source.close() }.toEither
    } yield decoded

    parsed match {
      case Left(exception) =>
        Future.failed(
          new Exception(
            s"invalid JSON for channel-info in ${file.toString}",
            exception
          )
        )
      case Right(channel) => Future.successful(channel)
    }
  }

  def parse(args: Array[String]): Future[CLIConfig] =
    parser.parse(args, new CLIConfig()) match {
      case None => Future.failed(new ScoptParsingFailure)
      case Some(config) => Future.successful(config)
    }

  def run(args: Array[String], getContainer: Future[Container]): Future[Unit] =
    for {
      config <- parse(args)
      channel <- decodeChannelInfo(config.channel)
      container <- getContainer
      _ <- set(config.packageId, config.organizationId, channel, config.output)(
        container
      )
    } yield ()

}
