// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.timeseries

case class AnnotationAggregateWindowResult[T](start: Long, end: Long, value: T)
