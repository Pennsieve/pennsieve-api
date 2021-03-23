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

package com.pennsieve.core.utilities

object PasswordBuddy {

  /* *
   *
   *  http://www.pleacher.com/mp/mlessons/algebra/entropy.html
   *
   *  Password strength is determined with this chart:
   *  < 28 bits = Very Weak; might keep out family members
   *  28 - 35 bits = Weak; should keep out most people, often good for desktop login passwords
   *  36 - 59 bits = Reasonable; fairly secure passwords for network and company passwords
   *  60 - 127 bits = Strong; can be good for guarding financial information
   *  128+ bits = Very Strong; often overkill
   *
   * */

  val hasUpper = (str: String) => str.exists(c => c.isUpper)

  val hasLower = (str: String) => str.exists(c => c.isLower)

  val hasSpecial = (str: String) =>
    str.exists(c => "~!@#$%^&*()_+/\\[]{}?><,.".contains(c))

  val hasNumbers = (str: String) => str.exists(c => "1234567890".contains(c))

  def findKeySpace(str: String): Double = {
    val crit: Seq[(String => Boolean, Int)] =
      List(hasUpper -> 26, hasLower -> 26, hasSpecial -> 24, hasNumbers -> 10)

    val score: Seq[Int] = crit map {
      case (f: (String => Boolean), weight: Int) =>
        if (f(str)) {
          weight
        } else {
          0
        }
    }

    score.toList.sum.toDouble
  }

  def passwordEntropy(password: String): Double = {

    //The entropy of a string is (approximately):
    //
    //   log2( R^L )
    //
    // where
    // L = number of characters
    // R = number of possible characters

    val lnOf2 = scala.math.log(2) // natural log of 2
    def log2(x: Double): Double = scala.math.log(x) / lnOf2
    val L = password.length.toDouble
    val R = findKeySpace(password)
    log2(scala.math.pow(R, L))
  }

}
