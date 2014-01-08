package com.soundcloud.spdt

import scala.util.hashing.MurmurHash3


class Featurizer(
  numClasses: Int,
  featureId: Int,
  numRealValuedVars: Int = 512,
  compression: Double = 1.0) {

  require(0 <= featureId && featureId < numClasses)

  val fIdL = featureId.toLong
  val nClsL = numClasses.toLong
  val nRVVL = numRealValuedVars.toLong

  val modSize: Long = ((2147483648L * compression).toLong - nRVVL) / (2L * nClsL)

  val offset = (1L + 2L * fIdL) * modSize + nRVVL

  // hash a string feature value into an int for use as a boolean feature
  def featurize(str: String): Int = {
    ((MurmurHash3.stringHash(str) % modSize) + offset).toInt
  }
}
