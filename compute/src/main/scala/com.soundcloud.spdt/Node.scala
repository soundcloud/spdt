package com.soundcloud.spdt

import scala.collection.immutable.HashMap
import breeze.linalg.{ DenseVector, sum }

case class Node(
  id:           Int,
  parentId:     Option[Int] = None,
  leftChildId:  Option[Int] = None,
  rightChildId: Option[Int] = None,
  feature:      Option[Int] = None,
  a:            Option[Double] = None,
  label:        Option[Int] = None,
  labelCounts:  DenseVector[Double] = DenseVector.zeros[Double](2),
  histograms:   HashMap[Key,Histogram] = HashMap[Key,Histogram]()) {

  if ((parentId.nonEmpty && label.isEmpty) || Some(id) == parentId) throw new RuntimeException(
    "Illegal node state! node=%s".format(this.toString))

  val isLeaf = leftChildId.isEmpty && rightChildId.isEmpty

  val isEmpty = sum(labelCounts) == 0.0

  // we don't want to store all false boolean histograms in node histograms
  // so it's possible to add them back here if they are absent
  def featureHistograms(featureId: Int, labels: List[Int]): HashMap[Key,Histogram] = {
    val relevant = HashMap(histograms.par.filter{case (key, hist) => {
      require(key.unapply.length == 2)
      key.unapply(0) == featureId
    }}.toList:_*)

    relevant.head._2 match {
      case h: RealValuedHistogram => relevant
      case _ => {
        val missing = labels.map(j =>
          if (relevant.contains(Key(featureId, j))) None else Some(j)).flatten

        val missingAllFalseBooleans = missing.map(j =>
          (Key(featureId, j), new BooleanHistogram(labelCounts(j), 0))).toList

        relevant ++ missingAllFalseBooleans
      }
    }
  }
}

object Node extends Pickling[Node] {
  def pickle(node: Node): String = {
    val histCount = node.histograms.count(h => true)
    val nodeInfo = List(
      node.id,
      optToA(node.parentId),
      optToA(node.leftChildId),
      optToA(node.rightChildId),
      optToA(node.feature),
      optToA(node.a),
      optToA(node.label),
      vecToA(node.labelCounts),
      triggerReadEles(histCount)
    ).mkString(":")

    val histInfo = node.histograms
      .toList
      .sortBy(kv => kv._1.unapply.mkString(""))
      .map{case (k, h) => Key.pickle(k) + ">" + Histogram.pickle(h)}
      .mkString("\n")

    "n|" + nodeInfo + (if (histCount > 0) "\n" + histInfo else "")
  }

  def fromPickle(pickled: String): Node = {
    val lines = pickled.split('\n')
    val Array(tag, classValString) = lines.head.split('|')
    require(tag == "n")

    val classVals = classValString.split(":")

    val id = classVals(0).toInt
    val parentId = aToOpt[Int](classVals(1))
    val leftChildId = aToOpt[Int](classVals(2))
    val rightChildId = aToOpt[Int](classVals(3))
    val feature = aToOpt[Int](classVals(4))
    val a = aToOpt[Double](classVals(5))
    val label = aToOpt[Int](classVals(6))
    val labelCounts = aToVec(classVals(7))
    val histCount = readReadEles(classVals(8))

    require(lines.length == histCount + 1)
    val hists = HashMap(lines.slice(1,lines.length).map{line => {
      val Array(keyStr, histStr) = line.split('>')
      Key.fromPickle(keyStr) -> Histogram.fromPickle(histStr)
    }}.toList:_*)

    new Node(
      id,
      parentId,
      leftChildId,
      rightChildId,
      feature,
      a,
      label,
      labelCounts,
      hists)
  }
}
