package com.soundcloud.spdt

import org.scalatest.FlatSpec


case class RealValuedHistogramTest() extends FlatSpec {

  behavior of "histogram allocation"

  it should "allocate a histogram given a number of bins" in {
    val hist = new RealValuedHistogram(100)
    assert(hist.maxBins === 100)
    assert(hist.bins === Vector())
  }

  it should "allocate a histogram given a set of bins" in {
    val hist = new RealValuedHistogram(Vector(Bin(1.0, 1), Bin(2.0, 2)), 100)
    assert(hist.maxBins === 100)
    assert(hist.numBins === 2)
    assert(hist.bins === Vector(Bin(1.0, 1), Bin(2.0, 2)))
  }


  behavior of "the update method"

  it should "update a point p by incrementing an existing bucket" in {
    var hist = new RealValuedHistogram(Vector(Bin(1.0, 1), Bin(2.0, 2)), 2)
    hist = hist.update(1.0)
    assert(hist.bins === Vector(Bin(1.0, 2), Bin(2.0, 2)))
  }

  it should "update a point p by merging it to the histogram" in {
    var hist = new RealValuedHistogram(Vector(Bin(1.0, 2), Bin(5.0, 1)), 2)
    hist = hist.update(1.75)
    assert(hist.bins === Vector(Bin(1.25, 3), Bin(5.0, 1)))
  }


  behavior of "the sum method"

  it should "estimate the sum of a histogram -inf to a point b" in {
    val hist = new RealValuedHistogram(
      Vector(Bin(2.0, 1), Bin(9.5, 2), Bin(19.33, 3), Bin(32.67, 3), Bin(45, 1)),
      100)
    assert(hist.sum(15.0) === 3.275550068354292)
    assert(hist.sum(9.5) === 2.0)
  }


  behavior of "the merge methods"

  it should "merge two histograms together" in {
    var hist = new RealValuedHistogram(
      Vector(Bin(2.0, 1), Bin(9.5, 2), Bin(17.5, 2), Bin(23.0, 1), Bin(36.0, 1)),
      5)
    val hist2 = new RealValuedHistogram(Vector(Bin(32.0, 1), Bin(30.0, 1), Bin(45.0, 1)), 3)

    hist = hist.merge(hist2)

    assert(hist.bins === Vector(
      Bin(2, 1),
      Bin(9.5, 2),
      Bin(19.333333333333332, 3),
      Bin(32.666666666666664, 3),
      Bin(45, 1))
    )
    assert(hist.numBins == 5)
  }

  it should "merge a set of histograms together if their maximum number of bins is the same" in {
    var hist = new RealValuedHistogram(
      Vector(Bin(2.0, 1), Bin(9.5, 2), Bin(17.5, 2), Bin(23.0, 1), Bin(36.0, 1)),
      5)
    val hist2 = new RealValuedHistogram(
      Vector(Bin(32.0, 1), Bin(30.0, 1), Bin(45.0, 1)), 5)

    val result = Histogram.merge(List(hist2, hist))

    assert(result.bins.toSet === Set(
      Bin(2, 1),
      Bin(9.5, 2),
      Bin(19.333333333333332, 3),
      Bin(32.666666666666664, 3),
      Bin(45, 1))
    )
    assert(result.numBins == 5)
  }


  behavior of "the uniform method"

  it should "uniformly distribute histogram samples between split points" in {
    val hist = new RealValuedHistogram(
      Vector(Bin(2.0, 1), Bin(9.5, 2), Bin(19.33, 3), Bin(32.67, 3), Bin(45, 1)),
      3)
    assert(hist.uniform === List(15.220950862145937, 28.964444444444442))
  }
}


case class BooleanHistogramTest() extends FlatSpec {

  behavior of "histogram allocation"

  it should "allocate a histogram" in {
    val hist = new BooleanHistogram()
    assert(hist.numBins === 2)
    assert(hist.bins === Vector(Bin(0.0, 0), Bin(1.0, 0)))
  }

  it should "allocate a histogram given a set of bins" in {
    val hist = new BooleanHistogram(Vector(Bin(0.0, 1), Bin(1.0, 2)))
    assert(hist.numBins === 2)
    assert(hist.bins === Vector(Bin(0.0, 1), Bin(1.0, 2)))
  }


  behavior of "the update method"

  it should "update a point p by incrementing an existing bucket" in {
    var hist = new BooleanHistogram()
    hist = hist.update(1.0)
    assert(hist.bins === Vector(Bin(0.0, 0), Bin(1.0, 1)))
  }

  behavior of "the sum method"

  it should "get the sum of boolean value occurrences" in {
    val hist = new BooleanHistogram(Vector(Bin(0.0, 1), Bin(1.0, 2)))
    assert(hist.sum(0.0) === 1)
    assert(hist.sum(0.5) === 1)
    assert(hist.sum(1.0) === 3)
  }


  behavior of "the merge method"

  it should "merge two histograms together" in {
    var hist1 = new BooleanHistogram(Vector(Bin(0.0, 1), Bin(1.0, 2)))
    val hist2 = new BooleanHistogram(Vector(Bin(0.0, 3), Bin(1.0, 4)))

    hist1 = hist1.merge(hist2)
    assert(hist1.bins(0) === Bin(0.0, 4))
    assert(hist1.bins(1) === Bin(1.0, 6))
  }


  behavior of "the uniform method"

  it should "should return 0.5 as the only split value" in {
    val hist = new BooleanHistogram(1, 1)
    assert(hist.uniform === List(0.5))
  }

  it should "should return an empty list if either of the bins are empty" in {
    val hist = new BooleanHistogram(0, 0)
    assert(hist.uniform === List())
  }
}

