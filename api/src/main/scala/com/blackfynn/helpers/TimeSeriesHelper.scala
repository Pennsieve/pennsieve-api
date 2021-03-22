// Copyright (c) 2019 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.helpers

import cats.data.EitherT
import cats.instances.future._
import com.blackfynn.db.TimeSeriesAnnotation
import com.blackfynn.dtos.ChannelDTO
import com.blackfynn.helpers.APIContainers.SecureAPIContainer
import com.blackfynn.models.{ Channel, Package }
import com.blackfynn.timeseries.AnnotationAggregateWindowResult
import java.time.{ Instant, ZoneOffset, ZonedDateTime }

import com.blackfynn.domain.CoreError

import scala.concurrent.{ ExecutionContext, Future }

/** TimeSeries utility functions */
object TimeSeriesHelper {

  val Epoch: ZonedDateTime =
    ZonedDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC)

  /** Get the startTime for the given package */
  def getPackageStartTime(
    `package`: Package,
    secureContainer: SecureAPIContainer
  )(implicit
    ec: ExecutionContext
  ): EitherT[Future, CoreError, Long] =
    secureContainer.timeSeriesManager
      .getChannels(`package`)
      .map(getPackageStartTime)

  /** Get the package start time based on the given list of channels */
  def getPackageStartTime(channels: List[Channel]): Long =
    channels.map(_.start).min

  /** Get the package start time based on the given list of channels DTOs */
  def getPackageStartTime(channelDTOs: Seq[ChannelDTO]): Long =
    channelDTOs.map(_.content.start).min

  /** Given a new start time, reset the given channel to use the
    * newStartTime instead of "EPOCH"
    */
  def resetChannelStartTime(newStartTime: Long)(channel: Channel): Channel = {
    val newLastAnnotation =
      if (channel.lastAnnotation >= newStartTime)
        channel.lastAnnotation - newStartTime
      else 0

    channel.copy(
      start = channel.start - newStartTime,
      end = channel.end - newStartTime,
      lastAnnotation = newLastAnnotation,
      createdAt = Epoch
    )
  }

  /** Given a new start time, reset the given channel to use the
    * newStartTime instead of "EPOCH"
    */
  def resetChannelDTOStartTime(
    newStartTime: Long
  )(
    channel: ChannelDTO
  ): ChannelDTO = {
    val newLastAnnotation =
      if (channel.content.lastAnnotation >= newStartTime)
        channel.content.lastAnnotation - newStartTime
      else 0

    channel.copy(
      content = channel.content.copy(
        start = channel.content.start - newStartTime,
        end = channel.content.end - newStartTime,
        lastAnnotation = newLastAnnotation,
        createdAt = Epoch
      )
    )
  }

  /** Given a new start time, reset the given annotation to use the
    * newStartTime instead of "EPOCH"
    */
  def resetAnnotationStartTime(
    newStartTime: Long
  )(
    annotation: TimeSeriesAnnotation
  ): TimeSeriesAnnotation = {
    annotation.copy(
      start = annotation.start - newStartTime,
      end = annotation.end - newStartTime
    )
  }

  /** Given a new start time, reset the given annotation window to use
    * the newStartTime instead of "EPOCH"
    */
  def resetAnnotationWindowStartTime[A](
    newStartTime: Long
  )(
    annotation: AnnotationAggregateWindowResult[A]
  ): AnnotationAggregateWindowResult[A] = {
    annotation.copy(
      start = annotation.start - newStartTime,
      end = annotation.end - newStartTime
    )
  }
}
