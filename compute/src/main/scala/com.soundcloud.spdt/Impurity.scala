package com.soundcloud.spdt

import breeze.linalg.{ DenseVector, Axis, DenseMatrix, sum, * }


package object impurity {

  val lnOf2 = scala.math.log(2) // natural log of 2
  def log2(x: Double): Double = scala.math.log(x) / lnOf2

  // standard entropy measure
  def entropy(probs: List[Double]): Double =
    -probs.map(p => if (p == 0.0) 0.0 else p * log2(p)).sum

  def entropy(probs: DenseVector[Double]): Double =
    entropy(probs.toArray.toList)

  // IBM's gini index
  def gini(probs: List[Double]): Double = 1.0 - probs.map(p => p * p).sum
}
