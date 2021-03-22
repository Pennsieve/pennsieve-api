package com.blackfynn.managers

import org.scalatest.FlatSpec
import com.blackfynn.core.utilities.PasswordBuddy.passwordEntropy

class PasswordSpec extends FlatSpec {

  "bad passwords" should "be caught" in {
    assert(passwordEntropy("password") < 59)
  }

  "good passwords" should "be allowed" in {
    assert(passwordEntropy("SDjhgsad!3") > 59)
  }

  "narrowly following the rules" should "be allowed" in {
    assert(passwordEntropy("Password1!") > 59)
  }

}
