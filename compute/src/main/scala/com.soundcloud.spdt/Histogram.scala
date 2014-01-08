package com.soundcloud.spdt

import scala.math


object Bin extends Pickling[Bin] {
  def pickle(b: Bin) =
    List(b.p, b.m).map(Formatting.formatDecimal(_)).mkString(",")

  def fromPickle(s: String): Bin = {
    val Array(p, m) = s.split(',').map(_.toDouble)
    Bin(p, m)
  }
}

case class Bin(p: Double, m: Double)


object Histogram extends Pickling[Histogram] {

  def merge(mergeList: List[Histogram]): Histogram = {
    if (mergeList.isEmpty || mergeList.map(_.maxBins).distinct.length != 1)
      throw new RuntimeException(
        "Illegal merge list! mergeList=%s".format(mergeList.toString.slice(0,100)))

    mergeList.par.reduce(_.merge(_))
  }

  def pickle(hist: Histogram) = "h|%s:%s"
    .format(hist.maxBins, hist.bins.map(Bin.pickle(_)).mkString(":"))

  def fromPickle(s: String): Histogram = {
    val Array(tag, classValString) = s.split('|')
    require(tag == "h")
    val classVals = classValString.split(":").toList
    val maxBins = classVals.head.toInt
    val bins = classVals.tail.map(Bin.fromPickle(_)).toVector

    if (maxBins == 2 && bins.map(_.p) == Vector(0.0, 1.0)) {
      new BooleanHistogram(bins)
    } else {
      new RealValuedHistogram(bins, maxBins)
    }
  }
}


abstract class Histogram {

  val maxBins: Int
  val bins: Vector[Bin]

  def numBins = bins.length

  def update(p: Double): Histogram

  def merge(h2: Histogram): Histogram

  def uniform: List[Double]

  def sum(b: Double): Double

  val total: Double = if (bins.isEmpty) 0 else bins.par.map(_.m).reduce(_ + _)
}


class BooleanHistogram(val bins: Vector[Bin]) extends Histogram {

  if (bins.length != 2 || bins(0).p != 0.0 || bins(1).p != 1.0)
    throw new RuntimeException(
      "Illegal boolean histogram! bins=%s".format(bins.toString))

  val maxBins: Int = 2

  def this() = this(Vector[Bin](Bin(0.0, 0), Bin(1.0, 0)))

  def this(numFalse: Double, numTrue: Double) =
    this(Vector[Bin](Bin(0.0, numFalse), Bin(1.0, numTrue)))

  def update(p: Double): BooleanHistogram = {
    if (p != 0.0 && p != 1.0) throw new RuntimeException(
      "Illegal update point! p=%f".format(p))

    val newBins = if (p == 0.0) {
      Vector(Bin(0.0, bins(0).m + 1), bins(1))
    } else {
      Vector(bins(0), Bin(1.0, bins(1).m + 1))
    }
    new BooleanHistogram(newBins)
  }

  def merge(h2: Histogram): BooleanHistogram = {
    new BooleanHistogram(Vector(
      Bin(0.0, h2.bins(0).m + bins(0).m),
      Bin(1.0, h2.bins(1).m + bins(1).m)))
  }

  def uniform: List[Double] =
    if (bins(0).m > 0 && bins(1).m > 0) List(0.5) else List()

  def sum(b: Double): Double = {
    if (!List(0.0, 0.5, 1.0).contains(b)) throw new RuntimeException(
      "Illegal sum point! b=%f".format(b))
    bins(0).m + (if (b == 1.0) bins(1).m else 0.0)
  }
}

class RealValuedHistogram(val bins: Vector[Bin], val maxBins: Int) extends Histogram {

  def this(mBins: Int) = this(Vector[Bin](), mBins)

  def update(p: Double): RealValuedHistogram = {
    val currentInd = bins.map(_.p).indexOf(p)

    val newBins = if (currentInd != -1) {
      val current = bins(currentInd)
      (bins.slice(0, currentInd) :+ Bin(current.p, current.m + 1)) ++
        (if (currentInd+1 < bins.length) bins.slice(currentInd+1, bins.length) else Nil)
    } else {
      var nb = (bins :+ Bin(p, 1)).sortBy(_.p)
      if (nb.length > maxBins) {
        if (nb.length > maxBins + 1) throw new RuntimeException(
          "Illegal histogram state length! nb.length=%d, maxBins=%d".format(nb.length, maxBins))
        nb = replace(nb, minDiff(nb))
      }
      nb
    }
    new RealValuedHistogram(newBins, maxBins)
  }

  def merge(h2: Histogram): RealValuedHistogram = {
    var newBins = (bins ++ h2.bins).sortBy(_.p)

    val numIdenticals = (newBins.length - newBins.map(_.p).distinct.length)

    0.until(numIdenticals).foreach(i => {
      newBins = replace(newBins, minDiff(newBins))
    })

    val sizeDiff = newBins.length - maxBins

    0.until(sizeDiff).foreach(i => {
      newBins = replace(newBins, minDiff(newBins))
    })
    new RealValuedHistogram(newBins, maxBins)
  }

  def uniform: List[Double] = {
    if (bins.length <= 1) return List[Double]()

    var i = 0

    0.until(maxBins - 1).map( j => {
      val s = (j.toDouble + 1.0) / maxBins.toDouble * total

      while (sum(bins(i+1).p) < s) {
        i += 1
      }

      val (pi, mi, pip1, mip1) = unpackInterval(bins, i)

      val d = s - sum(bins(i).p)
      val a = mip1 - mi
      val b = 2.0 * mi
      val c = -2.0 * d

      val z = if (a != 0) {
        (-b + math.pow(b * b - 4.0 * a * c, 0.5)) / (2.0 * a)
      } else {
        - c / b
      }

      val uj = pi + (pip1 - pi) * z
      if (uj equals Double.NaN) None else Some(uj)
    }).toList.flatten
  }

  def sum(b: Double): Double = {
    if (b >= bins.last.p) {
      return total
    } else if (b < bins.head.p) {
      return 0.0
    }

    val i = findInterval(bins, b)
    val (pi, mi, pip1, mip1) = unpackInterval(bins, i)

    val mb = mi + (mip1 - mi) / (pip1 - pi) * (b - pi)
    val s  = (mi + mb) / 2.0 * (b - pi) / (pip1 - pi)

    s + bins.take(i).map(_.m).sum + mi / 2.0
  }

  private def minDiff(binsState: Vector[Bin]): Int = {
    val diff = binsState.sliding(2)
      .toList
      .par
      .map(pair => pair(1).p - pair(0).p)

    diff.indexOf(diff.min)
  }

  private def replace(binsState: Vector[Bin], ind: Int): Vector[Bin] = {
    val (pi, mi, pip1, mip1) = unpackInterval(binsState, ind)

    val newM  = (mi + mip1)
    val newP  = (pi * mi + pip1 * mip1) / (mi + mip1)

    (binsState.slice(0,ind) :+ Bin(newP, newM)) ++
      (if (ind+2 < binsState.length) binsState.slice(ind+2, binsState.length) else Nil)
  }

  private def findInterval(binsState: Vector[Bin], b: Double): Int = {
    val stateLen = binsState.length

    if (b >= binsState.last.p) throw new java.lang.RuntimeException(
      "point p=%f is not located in any bin interval. \n %s".format(b, binsState.map(_.p)))
    if (stateLen < 2) throw new java.lang.RuntimeException(
      "Illegal state length! stateLen=%d".format(stateLen))

    var imin = 0
    var imax = stateLen - 1
    var imid = -1
    var continue = true

    while (continue && imax >= imin) {
      imid = imin + (imax - imin) / 2

      if (imid == stateLen - 2) {
        continue = false
      } else if (binsState(imid+1).p > b && binsState(imid).p <= b) {
        continue = false
      } else if (binsState(imid+1).p <= b) {
        imin = imid + 1
      } else {
        imax = imid - 1
      }
    }
    imid
  }

  private def unpackInterval(
    binsState: Vector[Bin],
    i: Int): (Double, Double, Double, Double) = {

    val mi    = binsState(i).m
    val mip1  = binsState(i+1).m
    val pi    = binsState(i).p
    val pip1  = binsState(i+1).p
    (pi, mi, pip1, mip1)
  }
}

