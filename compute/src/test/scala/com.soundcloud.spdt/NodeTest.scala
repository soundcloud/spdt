package com.soundcloud.spdt

import org.scalatest.FlatSpec

import breeze.linalg.DenseVector
import scala.collection.immutable.HashMap


case class NodeTest() extends FlatSpec {

  behavior of "the featureHistograms method"

  it should "fill in all false boolean histograms" in {
    val node = Node(
      id = 1,
      labelCounts = DenseVector(3.0, 5.0),
      histograms = HashMap(
        Key(0, 1) -> new BooleanHistogram(2, 3),
        Key(1, 1) -> new BooleanHistogram(4, 0)))

    val expected = HashMap(
      Key(0, 1) -> Vector(Bin(0, 2), Bin(1,3)),
      Key(0, 0) -> Vector(Bin(0, 3), Bin(1,0)))

    val result = node.featureHistograms(0, List(0, 1))
      .map{case (k, h) => k -> h.bins}.toMap

    assert(result === expected)
  }


  it should "return real-valued histograms as they are" in {
    val bins = Vector(Bin(0.1, 1.0), Bin(0.4, 2.0))

    val node = Node(
      id = 1,
      labelCounts = DenseVector(3.0, 5.0),
      histograms = HashMap(
        Key(0, 1) -> new RealValuedHistogram(bins, 10),
        Key(0, 0) -> new RealValuedHistogram(bins, 10)))

    val expected = HashMap(
      Key(0, 1) -> bins,
      Key(0, 0) -> bins)

    val rawResult = node.featureHistograms(0, List(0, 1))
    val result = rawResult.map{case (k, h) => k -> h.bins}.toMap

    val isRealValued = rawResult.head._2 match {
      case h: RealValuedHistogram => true
      case _ => false
    }

    assert(isRealValued)
    assert(result === expected)
  }
}

