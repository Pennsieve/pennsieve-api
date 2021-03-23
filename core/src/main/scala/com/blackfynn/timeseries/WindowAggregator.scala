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
