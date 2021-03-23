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

package com.pennsieve.timeseries

trait WindowAggregator[A, F] {

  val initialValue: A

  def aggregator(accumulator: A, annotationData: Option[AnnotationData]): A

  def postAggregator(accumulation: A): F

  def filter(value: F): Boolean

  def isEmpty(data: Option[AnnotationData]): Boolean
}

object WindowAggregator {

  object CountAggregator extends WindowAggregator[Long, Double] {

    override val initialValue: Long = 0L

    override def aggregator(
      accumulator: Long,
      annotationData: Option[AnnotationData]
    ): Long =
      accumulator + 1

    override def postAggregator(accumulation: Long): Double =
      accumulation.toDouble

    override def filter(value: Double): Boolean = value > 0

    def isEmpty(data: Option[AnnotationData]): Boolean = false
  }

  object IntegerAverageAggregator
      extends WindowAggregator[(Long, Long), Double] {

    override val initialValue: (Long, Long) = (0L, 0L)

    override def aggregator(
      accumulator: (Long, Long),
      annotationData: Option[AnnotationData]
    ): (Long, Long) = {
      val (total, count) = accumulator
      val updatedCount = count + 1
      annotationData match {
        case Some(Integer(v)) => (total + v, updatedCount)
        case _ => (total, updatedCount)
      }
    }

    override def postAggregator(accumulation: (Long, Long)): Double = {
      val (total, count) = accumulation
      if (count == 0) 0.toDouble
      else total.toDouble / count.toDouble
    }

    override def filter(value: Double): Boolean = value > 0

    override def isEmpty(data: Option[AnnotationData]): Boolean = data match {
      case Some(Integer(value)) => value == 0
      case _ => false
    }
  }
}
