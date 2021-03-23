package com.pennsieve.timeseries

import akka.NotUsed
import akka.stream.scaladsl.Flow

import scala.annotation.tailrec
import scala.collection.mutable

object AnnotationWindow {

  type Window = (Long, Long)

  def windowsFor(
    end: Long,
    period: Long
  )(
    startTimestamp: Long,
    endTimeStamp: Long
  ): Set[Window] = {
    @tailrec
    def createWindows(windowStart: Long, windows: Set[Window]): Set[Window] = {
      val windowEnd = windowStart + period match {
        case we if we > end => end
        case we => we
      }
      if (windowStart >= endTimeStamp || windowStart > end) windows
      else
        createWindows(
          windowStart + period,
          windows + (windowStart -> windowEnd)
        )
    }

    val windowStart = (startTimestamp / period) * period
    createWindows(windowStart, Set.empty)
  }

  class CommandGenerator(windowsFor: (Long, Long) => Set[Window]) {
    private val openWindows: mutable.Set[Window] = mutable.Set.empty

    def forEvent(event: AnnotationEvent): List[WindowCommand] = {
      val eventWindows = windowsFor(event.start, event.end)

      val closeCommands = openWindows.flatMap { window =>
        if (window._2 < event.start) {
          openWindows.remove(window)
          Some(CloseWindow(window))
        } else None
      }

      val openCommands = eventWindows.flatMap { w =>
        if (!openWindows.contains(w)) {
          openWindows.add(w)
          Some(OpenWindow(w))
        } else None
      }

      val addCommands = eventWindows.map(w => AddToWindow(event, w))

      openCommands.toList ++ closeCommands.toList ++ addCommands.toList
    }
  }

  sealed trait WindowCommand {
    def window: Window
  }

  case class OpenWindow(window: Window) extends WindowCommand
  case class CloseWindow(window: Window) extends WindowCommand
  case class AddToWindow(ev: AnnotationEvent, window: Window)
      extends WindowCommand

  def windowFlow[A, T](
    frameEnd: Long,
    periodLength: Long,
    windowAggregator: WindowAggregator[A, T]
  ): Flow[AnnotationEvent, AnnotationAggregateWindowResult[T], NotUsed] =
    Flow[AnnotationEvent]
      .statefulMapConcat { () =>
        val generator = new CommandGenerator(windowsFor(frameEnd, periodLength))
        event => generator.forEvent(event)
      }
      .groupBy(100, _.window)
      .takeWhile(!_.isInstanceOf[CloseWindow])
      .fold(
        AnnotationAggregateWindowResult(
          start = 0L,
          end = 0L,
          windowAggregator.initialValue
        )
      ) {
        case (agg, OpenWindow(w)) => agg.copy(start = w._1, end = w._2)
        case (agg, CloseWindow(_)) => agg
        case (agg, AddToWindow(event, _)) =>
          agg.copy(value = windowAggregator.aggregator(agg.value, event.data))
      }
      .map { aggregation =>
        val finalValue = windowAggregator.postAggregator(aggregation.value)
        aggregation.copy(value = finalValue)
      }
      .filter(aggregation => windowAggregator.filter(aggregation.value))
      .mergeSubstreams
}
