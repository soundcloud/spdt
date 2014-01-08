package com.soundcloud.spdt

import org.scalatest.FlatSpec

import scala.collection.immutable.{ HashMap, HashSet }
import java.util.concurrent.atomic.AtomicReference

import breeze.linalg.DenseVector

case class SPDTTest() extends FlatSpec {


  behavior of "the compress method"

  it should "compress samples into histograms" in {
    val samples = List(
      Sample(Map(0 -> 1.0, 1 -> 2.0, 2 -> 3.0), Some(0)),
      Sample(Map(0 -> 2.0, 1 -> 1.0, 2 -> 4.0), Some(0)))
    var tree = new DecisionTree()
    tree = tree.addDecision(0, 0, 1.5, 0, 1)
    val spdt = new SPDT(List(0, 1), 0.5, numBins = 10, tree = tree)
    val Some((hists,counts)) = spdt.compress(samples, 0)

    assert(hists.keys.toList.length === 6)

    assert(counts === HashMap(1 -> DenseVector(1.0, 0.0), 2 -> DenseVector(1.0, 0.0)))

    // need to use bins only because each histogram is instantiated from
    // an abstract class
    val test = hists.map{case (key, value) => key -> value.bins}.toMap
    assert(test.toSet === Set(
      Key(1, 0, 0) -> List(Bin(1.0, 1)),
      Key(1, 1, 0) -> List(Bin(2.0, 1)),
      Key(1, 2, 0) -> List(Bin(3.0, 1)),
      Key(2, 0, 0) -> List(Bin(2.0, 1)),
      Key(2, 1, 0) -> List(Bin(1.0, 1)),
      Key(2, 2, 0) -> List(Bin(4.0, 1))))
  }

  // TODO: Add this back if possible
  ignore should "filter histograms with totals below feature occurence threshold" in {
    val samples = List(
      Sample(Map(0 -> 1.0, 1 -> 1.0, 9 -> 1.0), Some(0)),
      Sample(Map(2 -> 1.0, 3 -> 1.0, 9 -> 1.0), Some(0)))
    var tree = new DecisionTree()
    tree = tree.addDecision(0, 9, 0.5, 0, 1)
    val spdt = new SPDT(List(0, 1), 0.5,
      numBins = 10,
      featureTypes = Some(Map()),
      tree = tree,
      minFeatureFrequency = Some(2.0 / 3.0))
    val Some((hists,counts)) = spdt.compress(samples, 0)

    assert(hists.keys.toList.length === 1)
    assert(counts === HashMap(2 -> DenseVector(2.0, 0.0)))

    // need to use bins only because each histogram is instantiated from
    // an abstract class
    val test = hists.map{case (key, value) => key -> value.bins}.toMap
    assert(test === Map(Key(2, 9, 0) -> List(Bin(0.0, 0), Bin(1.0, 2))))
  }

  it should "update nodes to which samples have traversed on iteration zero" in {
    val tree = new DecisionTree(HashMap[Int,Node](
      0 -> Node(0, None, Some(1), Some(2), Some(9), Some(1.5), None, DenseVector[Double](1.0, 1.0)),
      1 -> Node(1, Some(0), label = Some(-1), labelCounts = DenseVector[Double](1.0, 0.0)),
      2 -> Node(2, Some(0), label = Some(-1), labelCounts = DenseVector[Double](0.0, 0.0))), 2)

    val spdt = new SPDT(List(0, 1), 0.5,
      numBins = 10,
      tree = tree,
      minFeatureFrequency = None)

    val samples = List(
      Sample(Map(9 -> 1.0), Some(0)),
      Sample(Map(9 -> 2.0), Some(0)))

    val Some((hists,counts)) = spdt.compress(samples, 0)
    assert(hists.map{case (k, h) => k -> h.bins}.toMap === Map(
      Key(1, 9, 0) -> Vector(Bin(1, 1)),
      Key(2, 9, 0) -> Vector(Bin(2, 1))))
    assert(counts === HashMap(1 -> DenseVector(1.0, 0.0), 2 -> DenseVector(1.0, 0.0)))
  }

  it should "update only empty nodes on subsequent iterations" in {
    val tree = new DecisionTree(HashMap[Int,Node](
      0 -> Node(0, None, Some(1), Some(2), Some(9), Some(1.5), None, DenseVector(1.0, 1.0)),
      1 -> Node(1, Some(0), label = Some(-1), labelCounts = DenseVector(1.0, 0.0)),
      2 -> Node(2, Some(0), label = Some(-1), labelCounts = DenseVector(0.0, 0.0))), 2)

    val spdt = new SPDT(List(0, 1), 0.5,
      numBins = 10,
      tree = tree,
      minFeatureFrequency = None)

    val samples = List(
      Sample(Map(9 -> 1.0), Some(0)),
      Sample(Map(9 -> 2.0), Some(0)))

    val Some((hists,counts)) = spdt.compress(samples, 1)
    assert(hists.map{case (k, h) => k -> h.bins}.toMap === Map(
      Key(2, 9, 0) -> Vector(Bin(2, 1))))
    assert(counts === HashMap(2 -> DenseVector(1.0, 0.0)))
  }

  it should "fill in absent false values for in boolean histograms" in {
    val spdt = new SPDT(List(0, 1), 0.5, numBins = 10, featureTypes = Some(Map()))

    val samples = List(
      Sample(Map(0 -> 1.0), Some(0)),
      Sample(Map(1 -> 1.0), Some(0)),
      Sample(Map(0 -> 1.0), Some(1)),
      Sample(Map(1 -> 1.0), Some(1)),
      Sample(Map(1 -> 1.0), Some(1)))

    val Some((hists,counts)) = spdt.compress(samples, 0)

    assert(counts === HashMap(0 -> DenseVector(2.0, 3.0)))
    assert(hists.map{case (k, h) => k -> h.bins}.toMap === Map(
      Key(0, 0, 0) -> Vector(Bin(0.0, 1.0), Bin(1.0, 1.0)),
      Key(0, 1, 0) -> Vector(Bin(0.0, 1.0), Bin(1.0, 1.0)),
      Key(0, 0, 1) -> Vector(Bin(0.0, 2.0), Bin(1.0, 1.0)),
      Key(0, 1, 1) -> Vector(Bin(0.0, 1.0), Bin(1.0, 2.0))))
  }


  behavior of "the gather method and its helper-functions"

  it should "gather histograms and add decisions for real-valued histograms" in {
    val tree = new DecisionTree()
    var spdt = new SPDT(List(0, 1), 0.5, numBins = 3, tree = tree)

    val updatedMs = Map(
      keys(0) -> List(1, 1, 1),
      keys(1) -> List(1, 1, 3),
      keys(2) -> List(1, 1, 1),
      keys(3) -> List(4, 1, 1))

    spdt = spdt.gather(realValuedUpdateHists, realValuedUpdateLabelCounts)

    keys.foreach(key => {
      val Key(nId, i, j) = key
      val ijKey = Key(i, j)
      0.to(2).foreach(b => {
        assert(spdt.tree.graph(nId).histograms(ijKey).bins(b).p === (b+1).toDouble)
        assert(spdt.tree.graph(nId).histograms(ijKey).bins(b).m === updatedMs(key)(b))
      })
    })
    assert(spdt.tree.graph(0).feature.get === 1)
    assert(spdt.tree.graph(0).a.get === 2.2649110640673515)
  }

  it should "gather histograms and add decisions for boolean histograms" in {
    var spdt = new SPDT(List(0, 1), 0.5, Some(Map()), numBins = 3)

    val updatedMs = Map(
      keys(0) -> List(3, 1),
      keys(1) -> List(1, 3),
      keys(2) -> List(3, 1),
      keys(3) -> List(3, 1))

    spdt = spdt.gather(booleanUpdateHists, booleanUpdateLabelCounts)

    keys.foreach(key => {
      val Key(v, i, j) = key
      val ijKey = Key(i, j)
      0.to(1).foreach(b => {
        assert(spdt.tree.nodes(v).histograms(ijKey).bins(b).p === b.toDouble)
        assert(spdt.tree.nodes(v).histograms(ijKey).bins(b).m === updatedMs(key)(b))
      })
    })
    assert(spdt.tree.nodes(0).feature.get === 1)
    assert(spdt.tree.nodes(0).a.get === 0.5)
  }

  it should "calculate class probabilities for each side of a class split based on real-valued histograms" in {
    val spdt = new SPDT(List(0, 1), 0.5, numBins = 10)

    val n = Node(
      id = 0,
      histograms = realValuedNodeHists,
      labelCounts = DenseVector(10.0, 10.0))

    val (lCount, rCount, lProbs, rProbs, tau) =
      spdt.splitClassProbabilities(n, 0, 0.5)
    assert(lProbs === DenseVector(1.0 / 3.0, 2.0 / 3.0))
    assert(rProbs === DenseVector(0.38613861386138615, 0.6138613861386139))
    assert(tau === 0.2109375)
  }

  it should "calculate class probabilities for each side of a class split based on boolean histograms" in {
    val spdt = new SPDT(List(0, 1), 0.5, numBins = 10)

    val n = Node(
      id = 0,
      histograms = booleanNodeHists,
      labelCounts = DenseVector(5.0, 3.0))

    val (lCount, rCount, lProbs, rProbs, tau) =
      spdt.splitClassProbabilities(n, 0, 0.5)
    assert(lProbs === DenseVector(0.3333333333333333, 0.6666666666666666))
    assert(rProbs === DenseVector(0.6666666666666666, 0.3333333333333333))
    assert(tau === 0.5)
  }

  it should "calculate these split statistics, when class histograms are missing" in {
    val spdt = new SPDT(List(0, 1), 0.5, numBins = 10, featureTypes = Some(Map()))

    val nA = Node(
      id = 0,
      histograms = classCompleteBooleanNodeHists,
      labelCounts = DenseVector(5.0, 3.0))

    val nB = Node(
      id = 0,
      histograms = classMissingBooleanNodeHists,
      labelCounts = DenseVector(5.0, 3.0))

    val (lCount, rCount, lProbs, rProbs, tau) =
      spdt.splitClassProbabilities(nA, 0, 0.5)

    assert(lCount === 4)
    assert(rCount === 2)
    assert(lProbs === DenseVector(0.25, 0.75))
    assert(rProbs === DenseVector(1.0, 0.0))
    assert(tau === 2.0/3.0)
    assert(spdt.splitClassProbabilities(nA, 0, 0.5) ===
      spdt.splitClassProbabilities(nB, 0, 0.5))
  }

  it should "allocate a real-valued histogram if the feature type map is none" in {
    val spdt = new SPDT(List(0, 1), 0.5, numBins = 10)

    val hist = spdt.histogram(
      0,
      0,
      0,
      List(0,1),
      HashMap(0 -> DenseVector(0.0, 0.0)),
      HashMap(0 -> HashSet(0)))

    hist match {
      case h: RealValuedHistogram => assert(true)
      case _ => fail("allocated incorrect histogram type")
    }
  }

  it should "allocate the appropriate histogram if the feature type is mapped" in {
    val spdt = new SPDT(List(0, 1), 0.5, numBins = 10, featureTypes = Some(
      Map(0 -> Feature.Boolean, 1 -> Feature.RealValued)))

    val hist0 = spdt.histogram(
      0,
      0,
      0,
      List(0,1),
      HashMap(0 -> DenseVector(0.0, 0.0)),
      HashMap(0 -> HashSet(0)))

    val hist1 = spdt.histogram(
      1,
      0,
      0,
      List(0,1),
      HashMap(0 -> DenseVector(0.0, 0.0)),
      HashMap(0 -> HashSet(0)))

    hist0 match {
      case h: BooleanHistogram => assert(true)
      case _ => fail("allocated incorrect histogram type")
    }
    hist1 match {
      case h: RealValuedHistogram => assert(true)
      case _ => fail("allocated incorrect histogram type")
    }
  }

  it should "allocate a boolean histogram if the feature key is not found" in {
    val spdt = new SPDT(List(0, 1), 0.5, numBins = 10, featureTypes = Some(
      Map(1 -> Feature.RealValued)))

    val hist = spdt.histogram(
      0,
      0,
      0,
      List(0,1),
      HashMap(0 -> DenseVector(0.0, 0.0)),
      HashMap(0 -> HashSet(0)))

    hist match {
      case h: BooleanHistogram => assert(true)
      case _ => fail("allocated incorrect histogram type")
    }
  }


  behavior of "the prune method and its helper-functions"

  it should "given a node, compute it's error" in {
    val spdt = new SPDT(List(0, 1), 0.5, numBins = 10, errorWeight = Some(1.0))
    val n = Node(
      id = 0,
      feature = Some(0),
      a = Some(1.5),
      label = Some(1),
      labelCounts = DenseVector(2.0, 2.0))

    assert(spdt.error(n) === 2.0)
  }

  it should "given a leaf node, compute the MDL cost of it" in {
    var spdt = new SPDT(List(0, 1), 0.5, numBins = 3)

    spdt = spdt.gather(realValuedUpdateHists, realValuedUpdateLabelCounts)
    assert(spdt.cost(spdt.tree, spdt.tree.graph(1)) === (0.6931471805599453, false))
  }

  it should "given an interior node, compute the MDL cost of its subtree" in {
    var spdt = new SPDT(List(0, 1), 0.5, numBins = 3)
    spdt = spdt.gather(realValuedUpdateHists, realValuedUpdateLabelCounts)
    assert(spdt.cost(spdt.tree, spdt.tree.nodes(0)) === (3.079441541679836, false))
  }

  // TODO: Test histograms
  it should "prune a tree based on the MDL cost of subtrees" in {
    val tree = new DecisionTree(HashMap(
      0 -> Node(id = 0, leftChildId = Some(1), rightChildId = Some(2)),
      1 -> Node(id = 1, parentId = Some(0), label = Some(0)),
      2 -> Node(id = 2, parentId = Some(0), label = Some(1))), 2)

    var spdt = new SPDT(List(0, 1), 0.5, numBins = 3, tree = tree) {
      override def cost(t: DecisionTree, n: Node): (Double,Boolean) = {
        if (n.id == 0) (1000.0, true) else (1000.0, false)
      }
    }

    spdt = spdt.prune
    assert(spdt.tree.nodes.length === 1)
    assert(spdt.tree.nodes(0).id === 0)
    // TODO: must the root node have a label?
  }

  val realValuedNodeHists = HashMap[Key,Histogram](
    Key(0, 0) -> new RealValuedHistogram(Vector(Bin(0.0, 1), Bin(1.0, 2), Bin(2.0, 3)), 3),
    Key(1, 0) -> new RealValuedHistogram(Vector(Bin(0.0, 1), Bin(1.0, 2), Bin(2.0, 1)), 3),
    Key(0, 1) -> new RealValuedHistogram(Vector(Bin(0.0, 2), Bin(1.0, 3), Bin(2.0, 5)), 3))

  val booleanNodeHists = HashMap[Key,Histogram](
    Key(0, 0) -> new BooleanHistogram(Vector(Bin(0.0, 1), Bin(1.0, 2))),
    Key(1, 0) -> new BooleanHistogram(Vector(Bin(0.0, 1), Bin(1.0, 1))),
    Key(0, 1) -> new BooleanHistogram(Vector(Bin(0.0, 2), Bin(1.0, 1))))

  val classMissingBooleanNodeHists = HashMap[Key,Histogram](
    Key(0, 0) -> new BooleanHistogram(Vector(Bin(0.0, 1), Bin(1.0, 2))),
    Key(1, 0) -> new BooleanHistogram(Vector(Bin(0.0, 1), Bin(1.0, 1))))

  val classCompleteBooleanNodeHists = HashMap[Key,Histogram](
    Key(0, 0) -> new BooleanHistogram(Vector(Bin(0.0, 1), Bin(1.0, 2))),
    Key(1, 0) -> new BooleanHistogram(Vector(Bin(0.0, 1), Bin(1.0, 1))),
    Key(0, 1) -> new BooleanHistogram(Vector(Bin(0.0, 3), Bin(1.0, 0))))

  val keys = List(
    Key(0, 0, 0),
    Key(0, 1, 0),
    Key(0, 0, 1),
    Key(0, 1, 1))

  val realValuedUpdateHists = HashMap[Key,Histogram](
    keys(0) -> new RealValuedHistogram(Vector(Bin(1.0, 1), Bin(2.0, 1), Bin(3.0, 1)), 10),
    keys(1) -> new RealValuedHistogram(Vector(Bin(1.0, 1), Bin(2.0, 1), Bin(3.0, 3)), 10),
    keys(2) -> new RealValuedHistogram(Vector(Bin(1.0, 1), Bin(2.0, 1), Bin(3.0, 1)), 10),
    keys(3) -> new RealValuedHistogram(Vector(Bin(1.0, 4), Bin(2.0, 1), Bin(3.0, 1)), 10))

  val realValuedUpdateLabelCounts = HashMap[Int,DenseVector[Double]](0 -> DenseVector[Double](4.0, 6.0))

  val booleanUpdateHists = HashMap[Key,Histogram](
    keys(0) -> new BooleanHistogram(Vector(Bin(0.0, 3), Bin(1.0, 1))),
    keys(1) -> new BooleanHistogram(Vector(Bin(0.0, 1), Bin(1.0, 3))),
    keys(2) -> new BooleanHistogram(Vector(Bin(0.0, 3), Bin(1.0, 1))),
    keys(3) -> new BooleanHistogram(Vector(Bin(0.0, 3), Bin(1.0, 1))))

  val booleanUpdateLabelCounts = HashMap[Int,DenseVector[Double]](0 -> DenseVector[Double](4.0, 4.0))
}

