package com.soundcloud.spdt

import org.scalatest.{ FlatSpec, BeforeAndAfterEach }


case class TreeTest() extends FlatSpec with BeforeAndAfterEach {

  var tree: DecisionTree = _

  override def beforeEach() {
    tree = new DecisionTree()
  }


  behavior of "tree allocation"

  it should "allocate the tree with a root node" in {
    assert(tree.nodes === List(Node(0)))
    assert(tree.leaves === List(Node(0)))
  }


  behavior of "the add decision method"

  it should "add a decision" in {
    val newTree = tree.addDecision(0, 1, 0.2, 3, 4)
    assert(newTree.nodes.sortWith(_.id < _.id) === List(
      Node(0, None, Some(1), Some(2), Some(1), Some(0.2)),
      Node(1, Some(0), label = Some(3)),
      Node(2, Some(0), label = Some(4))))
  }

  it should "throw an exception if the parent does not exist" in {
    try {
      tree.addDecision(1, 1, 1, 1, 1)
      fail("should have thrown an exception")
    } catch {
      case _: Throwable => {}
    }
  }

  it should "return false if the parent is not a leaf" in {
    tree.addDecision(0, 1, 1, 1, 1)
    try {
      tree.addDecision(0, 1, 1, 1, 1)
      fail("should have thrown an exception")
    } catch {
      case _: Throwable => {}
    }
  }


  behavior of "the traverse method"

  it should "traverse a sample to the appropriate leaf node" in {
    tree = tree.addDecision(0, 0, 0.5, 0, 1)
    assert(tree.traverse(sample1) === 1)
    assert(tree.traverse(sample2) === 2)
  }

  it should "traverse a sample without the feature to the left child" in {
    tree = tree.addDecision(0, 0, 0.5, 0, 1)
    assert(tree.traverse(Sample(Map(5 -> 1.0), None)) == 1)
  }


  behavior of "the classify method"

  it should "classify a sample" in {
    tree = tree.addDecision(0, 0, 0.5, 0, 1)
    assert(tree.classify(sample1) === Some(0))
    assert(tree.classify(sample2) === Some(1))
  }


  behavior of "the prune method"

  it should "prune the children of a node" in {
    tree = tree.addDecision(0, 0, 0.5, 0, 1)
    tree = tree.addDecision(1, 1, 0.5, 0, 1)

    assert(tree.leaves.map(_.id).toSet === Set(2, 3, 4))
    assert(tree.nodes.map(_.id).toSet === Set(0, 1, 2, 3, 4))

    tree = tree.pruneChildrenOf(1)

    assert(tree.leaves.map(_.id).toSet === Set(1, 2))
    assert(tree.nodes.map(_.id).toSet === Set(0, 1, 2))
    assert(tree.nodes(1).isLeaf && tree.nodes(2).isLeaf)
  }


  behavior of "the update method"

  ignore should "update histograms in parallel" in {
  }

  val sample1 = Sample(Map(0 -> 0.4), None)
  val sample2 = Sample(Map(0 -> 0.6), None)
}

