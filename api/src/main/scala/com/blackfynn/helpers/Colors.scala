package com.blackfynn.helpers

import com.blackfynn.web.Settings

import scala.util.Random

object Colors {

  val pennsieveColors: Set[String] = Settings.colors.values.toSet

  def randomNewColor(
    existing: Set[String],
    toPickFrom: Set[String] = pennsieveColors
  ): Option[String] = {
    val pool = toPickFrom -- existing
    val indx = Random.nextInt(pool.size)
    pool.toList.lift(indx)
  }
}
