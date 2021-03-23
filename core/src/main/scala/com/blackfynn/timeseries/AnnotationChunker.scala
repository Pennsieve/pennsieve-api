package com.pennsieve.timeseries

import akka.stream.{ Attributes, FlowShape, Inlet, Outlet }
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }

// inspired by
// https://doc.akka.io/docs/akka/2.5.9/stream/stream-cookbook.html#chunking-up-a-stream-of-bytestrings-into-limited-size-bytestrings
class AnnotationChunker[A, F](
  val frameStart: Long,
  val frameEnd: Long,
  val periodLength: Long,
  val windowAggregator: WindowAggregator[A, F]
) extends GraphStage[
      FlowShape[AnnotationEvent, AnnotationAggregateWindowResult[A]]
    ] {

  val in: Inlet[AnnotationEvent] =
    Inlet[AnnotationEvent]("AnnotationChunker.in")
  val out: Outlet[AnnotationAggregateWindowResult[A]] =
    Outlet[AnnotationAggregateWindowResult[A]]("AnnotationChunker.out")
  override val shape
    : FlowShape[AnnotationEvent, AnnotationAggregateWindowResult[A]] =
    FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {

      private var accumulator = AnnotationAggregateWindowResult[A](
        start = frameStart,
        end = frameStart + periodLength,
        value = windowAggregator.initialValue
      )

      private def isAccumulatorEmpty: Boolean =
        accumulator.value == windowAggregator.initialValue

      private def getNextPeriod: (Long, Long) = {
        val periodEnd = (accumulator.end + periodLength) match {
          case pe if pe > frameEnd => frameEnd
          case pe => pe
        }
        accumulator.end -> periodEnd
      }

      private def periodContains(
        period: (Long, Long)
      )(
        event: AnnotationEvent
      ): Boolean =
        event.start >= period._1 && event.end <= period._2

      private def eventStartsInNextPeriod(event: AnnotationEvent): Boolean = {
        val nextPeriod = getNextPeriod
        event.start >= nextPeriod._1 && event.start <= nextPeriod._2
      }

      private def calculateNewPeriodEnd(
        oldPeriodStart: Long,
        eventEnd: Long
      ) = {
        (eventEnd - oldPeriodStart) % periodLength match {
          case 0 => eventEnd
          case remainder => (periodLength - remainder) + eventEnd
        }
      }

      private def timestampPeriodStart(timestamp: Long) =
        (timestamp / periodLength) * periodLength

      private def isEventEmpty(elem: AnnotationEvent): Boolean =
        windowAggregator.isEmpty(elem.data)

      /*
    handle the event in one of four ways
    1. event is empty:
      just pull a new element
    2. check if the event is in the current period and aggregate its' data if it is and pull a new element
    3. check if the event is in the next period and extend current accumulator to contain the period
      that will contain the new elem's end time and aggregate it's data
    4. the event is not in the current or next period so push out the current accumulator,
      create a new one which will contain the event's start and end times and aggregate it's data
       */
      private def process(event: AnnotationEvent): Unit = {
        if (!isEventEmpty(event)) {
          if (periodContains(accumulator.start -> accumulator.end)(event)) {
            accumulator = accumulator.copy(
              value = windowAggregator.aggregator(accumulator.value, event.data)
            )
            if (!isClosed(in)) pull(in) // make sure we the input port isn't closed before pulling
          } else if (eventStartsInNextPeriod(event)) {
            accumulator = accumulator.copy(
              end = calculateNewPeriodEnd(accumulator.start, event.end),
              value = windowAggregator.aggregator(accumulator.value, event.data)
            )
            if (!isClosed(in)) pull(in)
          } else {
            val chunk = accumulator
            val newStart = timestampPeriodStart(event.start)
            accumulator = AnnotationAggregateWindowResult(
              start = newStart,
              end = calculateNewPeriodEnd(newStart, event.end),
              value = windowAggregator
                .aggregator(windowAggregator.initialValue, event.data)
            )
            push(out, chunk)
          }
        } else {
          if (!isClosed(in)) pull(in)
        }
      }

      setHandlers(
        in,
        out,
        new OutHandler with InHandler {

          override def onPull(): Unit = {
            if (!isClosed(in) && !hasBeenPulled(in)) {
              pull(in)
            }
          }

          override def onPush(): Unit = {
            val event = grab(in)
            process(event)
          }

          override def onUpstreamFinish(): Unit = {
            if (isAccumulatorEmpty) completeStage()
            else if (isAvailable(out)) {
              push(out, accumulator)
              completeStage()
            }
          }
        }
      )
    }
}
