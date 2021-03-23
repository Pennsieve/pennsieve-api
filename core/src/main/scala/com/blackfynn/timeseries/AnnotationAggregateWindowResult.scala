// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.pennsieve.timeseries

case class AnnotationAggregateWindowResult[T](start: Long, end: Long, value: T)
