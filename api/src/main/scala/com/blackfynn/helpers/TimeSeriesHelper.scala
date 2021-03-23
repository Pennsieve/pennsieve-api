/*
 * Copyright 2021 University of Pennsylvania
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pennsieve.helpers

import cats.data.EitherT
import cats.instances.future._
import com.pennsieve.db.TimeSeriesAnnotation
import com.pennsieve.dtos.ChannelDTO
import com.pennsieve.helpers.APIContainers.SecureAPIContainer
import com.pennsieve.models.{ Channel, Package }
import com.pennsieve.timeseries.AnnotationAggregateWindowResult
import java.time.{ Instant, ZoneOffset, ZonedDateTime }

import com.pennsieve.domain.CoreError

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
