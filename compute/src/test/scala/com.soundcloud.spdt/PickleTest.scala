package com.soundcloud.spdt

import org.scalatest.FlatSpec
import scala.collection.immutable.HashMap

import java.util.concurrent.atomic.AtomicReference

import breeze.linalg.DenseVector


case class PickleTest() extends FlatSpec {

  behavior of "the bin pickler"

  it should "pickle a bin" in {
    assert(Bin.pickle(Bin(0.1, 2.0)) === "0.1,2.0")
  }

  it should "unpickle a bin" in {
    assert(Bin.fromPickle("0.1,2.0") === Bin(0.1, 2.0))
  }


  behavior of "the key pickler"

  it should "pickle a key" in {
    assert(Key.pickle(Key(1,2,3)) === "k|1:2:3")
  }

  it should "unpickle a key" in {
    assert(Key.fromPickle("k|1:2:3") === Key(1,2,3))
  }

  behavior of "thie histogram pickler"

  it should "pickle a real valued histogram" in {
    assert(Histogram.pickle(
      new RealValuedHistogram(Vector(Bin(0.1, 2.0), Bin(0.2, 3.0)),100)) ===
      "h|100:0.1,2.0:0.2,3.0")
  }

  it should "unpickle a real valued histogram" in {
    val unpickled = Histogram.fromPickle("h|100:0.1,2.0:0.2,3.0")
    val expected = new RealValuedHistogram(Vector(Bin(0.1, 2.0), Bin(0.2, 3.0)),100)
    assert(unpickled.bins === expected.bins)
    assert(unpickled.maxBins === expected.maxBins)
  }

  it should "pickle a boolean histogram" in {
    assert(Histogram.pickle(new BooleanHistogram(2,3)) ===
      "h|2:0.0,2.0:1.0,3.0")
  }

  it should "unpickle a boolean histogram" in {
    val unpickled = Histogram.fromPickle("h|2:0.0,2.0:1.0,3.0")
    val expected = new BooleanHistogram(2,3)
    assert(unpickled.bins === expected.bins)
  }


  behavior of "the node pickler"

  val pickledLeaf = "n|0:_:_:_:_:_:_:0.0,0.0:[0]"
  val pickledNtrnl = "n|1:0:3:4:5:0.5:1:0.0,80.0:[2]\n" +
    "k|0:1>h|2:0.0,10.0:1.0,20.0\n" +
    "k|1:1>h|2:0.0,20.0:1.0,30.0"

  val leaf = Node(0)
  val ntrnl = Node(
    1,
    Some(0),
    Some(3),
    Some(4),
    Some(5),
    Some(0.5),
    Some(1),
    DenseVector[Double](0.0,80.0),
    HashMap(
      Key(0,1) -> new BooleanHistogram(10,20),
      Key(1,1) -> new BooleanHistogram(20,30)))

  it should "pickle a node" in {
    assert(Node.pickle(leaf) === pickledLeaf)
    assert(Node.pickle(ntrnl) === pickledNtrnl)
  }

  it should "unpickle a node" in {
    val extractedLeaf = Node.fromPickle(pickledLeaf)
    val extractedNtrnl = Node.fromPickle(pickledNtrnl)
    assert(extractedLeaf === leaf)
    assert(extractedNtrnl.copy(histograms = HashMap()) === ntrnl.copy(histograms = HashMap()))

    assert(extractedNtrnl.histograms(Key(0,1)).bins === ntrnl.histograms(Key(0,1)).bins)
    assert(extractedNtrnl.histograms(Key(0,1)).maxBins === ntrnl.histograms(Key(0,1)).maxBins)
    assert(extractedNtrnl.histograms(Key(1,1)).bins === ntrnl.histograms(Key(1,1)).bins)
    assert(extractedNtrnl.histograms(Key(1,1)).maxBins === ntrnl.histograms(Key(1,1)).maxBins)
  }


  behavior of "the tree pickler"

  val newTree = "tree|2:[1]\n" +
    "n|0:_:_:_:_:_:_:0.0,0.0:[0]"

  val pickledTree = "tree|2:[3]\n" +
    "n|0:_:1:2:0:0.5:1:0.0,80.0:[2]\n" +
    "k|0:1>h|2:0.0,10.0:1.0,20.0\n" +
    "k|1:1>h|2:0.0,20.0:1.0,30.0\n" +
    "n|1:0:_:_:_:_:0:0.0,40.0:[1]\n" +
    "k|0:1>h|2:0.0,10.0:1.0,20.0\n" +
    "n|2:0:_:_:_:_:1:0.0,40.0:[1]\n" +
    "k|1:1>h|2:0.0,20.0:1.0,30.0"

  val nodes = HashMap(
    0 -> Node(
      0,
      None,
      Some(1),
      Some(2),
      Some(0),
      Some(0.5),
      Some(1),
      DenseVector[Double](0.0, 80.0),
      HashMap(
        Key(0,1) -> new BooleanHistogram(10,20),
        Key(1,1) -> new BooleanHistogram(20,30))),
    1 -> Node(
      id = 1,
      parentId = Some(0),
      label = Some(0),
      labelCounts = DenseVector[Double](0.0, 40.0),
      histograms = HashMap(
        Key(0,1) -> new BooleanHistogram(10,20))),
    2 -> Node(
      id = 2,
      parentId = Some(0),
      label = Some(1),
      labelCounts = DenseVector[Double](0.0, 40.0),
      histograms = HashMap(
        Key(1,1) -> new BooleanHistogram(20,30))))

  it should "pickle a tree" in {
    assert(DecisionTree.pickle(new DecisionTree()) === newTree)
    assert(DecisionTree.pickle(new DecisionTree(nodes, 2)) === pickledTree)
  }

  it should "unpickle a tree" in {
    val extracted = DecisionTree.fromPickle(pickledTree)
    nodes.foreach{case (i, n) => {

      assert(
        extracted.graph(i).copy(histograms = HashMap()) ===
        n.copy(histograms = HashMap()))

      n.histograms.foreach{case (k, h) => {
        assert(
          extracted.graph(i).histograms(k).bins ===
          h.bins)
        assert(
          extracted.graph(i).histograms(k).maxBins ===
          h.maxBins)
      }}
    }}
  }


  behavior of "the SPDT pickler"

  val tree = new DecisionTree()
  val spdt = new SPDT(List(0, 1), 0.05, tree = tree)

  val pickledSPDT = "spdt|0,1:0.05:_:1.0:_:0.0:0.0:100:[1]\n" +
    "tree|2:[1]\n" +
    "n|0:_:_:_:_:_:_:0.0,0.0:[0]"

  it should "pickle an spdt instance" in {
    assert(SPDT.pickle(spdt) === pickledSPDT)
  }

  it should "unpickle an spdt instance" in {
    assert(SPDT.fromPickle(pickledSPDT).copy(tree = tree) === spdt)
  }
}
