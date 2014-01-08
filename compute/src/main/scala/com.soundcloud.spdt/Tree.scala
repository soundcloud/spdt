package com.soundcloud.spdt

import scala.collection.immutable.HashMap
import System.{ currentTimeMillis => now }
import java.lang.RuntimeException

import java.io.{ BufferedWriter, File, FileWriter }

import breeze.linalg.DenseVector


class DecisionTree(val graph: HashMap[Int, Node],
                   val numLabels: Int) extends Serializable {

  def this() = this(DecisionTree.empty(2), 2)
  def this(numLabels: Int) = this(DecisionTree.empty(numLabels), numLabels)

  val leaves: List[Node] = graph
    .filter{case (k, n) => n.isLeaf}
    .map{case(k, n) => n}
    .toList
    .sortWith(_.id < _.id)

  val nodes: List[Node] =
    graph.toList.sortWith(_._1 < _._1).map(_._2).toList

  protected val leafIndexMap = leaves.map(_.id).zipWithIndex.toMap

  val numNodes = nodes.length

  def leafIndex(nId: Int) = leafIndexMap(nId) //leaves.map(_.id).indexOf(nId)

  // update histograms in lock-step
  // this makes synchronization easier
  def updateHistograms(
    updates: Iterable[(Key,Histogram)],
    labelCountByNode: HashMap[Int,DenseVector[Double]],
    isRealValued: Int => Boolean) = {

    val groupedUpdates = updates
      .groupBy(_._1.unapply(0))
      .mapValues(histTuples => histTuples.map{case (k,hist) =>
        Key(k.unapply.slice(1,3):_*) -> hist
      }).toVector

    val updatedNodes = groupedUpdates.par.map{case (nId, updateMaps) => {
      val newHists = HashMap((updateMaps ++ graph(nId).histograms.toVector)
        .groupBy(_._1)
        .mapValues(histTuples => histTuples.par.map(_._2).reduce((l,r) => l.merge(r)))
        .toList:_*)

      nId -> graph(nId).copy(
        histograms = graph(nId).histograms ++ newHists,
        labelCounts = graph(nId).labelCounts + labelCountByNode(nId))
    }}

    new DecisionTree(graph ++ updatedNodes, numLabels)
  }

  def addDecision(
    parentId: Int,
    feature: Int,
    a: Double,
    leftChildLabel: Int,
    rightChildLabel: Int): DecisionTree = {

    if (!exists(parentId) || !isLeaf(parentId)) throw new RuntimeException(
      "Cannot add decision! parentId=%d, exists(parentId)=%s, isLeaf(parentId)=%s".format(
         parentId, exists(parentId), isLeaf(parentId))
    )

    val currentMaxId = leaves.last.id
    val (leftChildId, rightChildId) = (currentMaxId + 1, currentMaxId + 2)
    val emptyCounts = DenseVector.zeros[Double](numLabels)

    // add child nodes
    val left = leftChildId -> Node(
      leftChildId,
      Some(parentId),
      labelCounts = emptyCounts,
      label = Some(leftChildLabel))

    val right = rightChildId -> Node(
      rightChildId,
      Some(parentId),
      labelCounts = emptyCounts,
      label = Some(rightChildLabel))

    // update the parent node with the split
    val parent = parentId -> graph(parentId).copy(
      leftChildId = Some(leftChildId),
      rightChildId = Some(rightChildId),
      feature = Some(feature),
      a = Some(a))

    new DecisionTree(graph ++ Map(parent, left, right), numLabels)
  }

  def pruneChildrenOf(parentId: Int): DecisionTree = {
    if (!exists(parentId)) throw new RuntimeException(
      "Cannot prune children! parentId=%d, exists(parentId)=%s".format(
         parentId, exists(parentId))
    )

    var toTraverse = new scala.collection.immutable.Stack[Int]()
    var toPrune = List[Int](parentId)

    if (!graph(parentId).isLeaf) { toTraverse = toTraverse.push(parentId) }

    while (toTraverse.nonEmpty) {
      val current = toTraverse.top
      toTraverse = toTraverse.pop

      if (!graph(current).isLeaf) {
        val children = List(left(current).get.id, right(current).get.id)
        toPrune = children ++ toPrune
        toTraverse = toTraverse.pushAll(children)
      }
      toPrune = current :: toPrune
    }

    val parent = parentId -> graph(parentId).copy(
      leftChildId = None,
      rightChildId = None,
      a = None,
      feature = None)

    new DecisionTree((graph -- toPrune) + parent, numLabels)
  }

  def traverse(s: Sample, trace: Node => Unit = (n: Node) => {}): Int = {
    var ptr = 0
    while (!isLeaf(ptr)) {
      trace(graph(ptr))
      val fId = graph(ptr).feature.get

      // if not defined the feature can't satisfy the criteria or is boolean
      if (s.features.getOrElse(fId, 0.0) < graph(ptr).a.get) {
        ptr = graph(ptr).leftChildId.get
      } else {
        ptr = graph(ptr).rightChildId.get
      }
    }
    return ptr
  }

  def classify(s: Sample): Option[Int] = graph(traverse(s)).label

  def exists(nodeId: Int): Boolean = graph.contains(nodeId)

  def isLeaf(ofNodeId: Int): Boolean =
    graph.contains(ofNodeId) &&
    graph(ofNodeId).isLeaf

  def left(ofNode: Node): Option[Node] = graph.get(ofNode.leftChildId.get)

  def left(ofNodeId: Int): Option[Node] = left(graph(ofNodeId))

  def right(ofNode: Node): Option[Node] = graph.get(ofNode.rightChildId.get)

  def right(ofNodeId: Int): Option[Node] = right(graph(ofNodeId))

  def parent(ofNode: Node): Option[Node] =
    if (ofNode.parentId.isDefined) graph.get(ofNode.parentId.get) else None

  def parent(ofNodeId: Int): Option[Node] = parent(graph(ofNodeId))

  def dotfile(filename: String, featureMapFilename: Option[String] = None) {

    val nameMap = if (featureMapFilename.isDefined) {
      val hashes = scala.io.Source.fromFile(featureMapFilename.get)
        .getLines
        .map(line => {
          val Array(hashVal, string) = line.split("\t")
          hashVal.toInt -> string
        })
        .toMap + (-1 -> "NA")

      (n: Node) => "id=%d.%s.label=%s".format(
        n.id,
        hashes(n.feature.getOrElse(-1)) +
          (if (n.a.isDefined) "@" + "%.2f".format(n.a.get) else ""),
        n.label.getOrElse("None").toString)
    } else {
      (n: Node) => "id=%d.label=%s".format(n.id, n.label.getOrElse("None").toString)
    }

    val dotfile = new File(filename)
    if (!dotfile.exists()) dotfile.createNewFile

    val fw = new FileWriter(dotfile.getAbsoluteFile)
    val bw = new BufferedWriter(fw)

    try {
      bw.write("digraph Tree {\n")

      for (node <- nodes) {
        if (node.parentId.isDefined)
          bw.write("\"%s\" -> \"%s\"\n".format(
            nameMap(graph(node.parentId.get)),
            nameMap(node)))
      }

      bw.write("}")
    } finally {
      bw.close
    }
  }

}


object DecisionTree extends Pickling[DecisionTree] {

  def empty(n: Int) =
    HashMap[Int,Node](0 -> Node(0, labelCounts = DenseVector.zeros[Double](n)))

  def pickle(tree: DecisionTree): String = {
    val treeInfo =
      "tree|%d:%s".format(tree.numLabels, triggerReadEles(tree.numNodes))
    val nodeInfo = tree.graph.map{case (i, n) => Node.pickle(n)}.toList

    require(tree.numNodes == nodeInfo.length)

    treeInfo + (
      if (nodeInfo.nonEmpty) "\n%s".format(nodeInfo.mkString("\n")) else "")
  }

  def fromPickle(pickled: String): DecisionTree = {
    val lines = pickled.split("\n")
    val Array(tag, classVarStr) = lines.head.split('|')
    require(tag == "tree")

    val classVars = classVarStr.split(':')
    val numLabels = classVars(0).toInt
    val numReadLines = readReadEles(classVars(1))

    var ptr = 1
    var map = HashMap[Int,Node]()
    while (ptr < lines.length) {
      val numAhead = readReadEles(lines(ptr).split(':').last) + 1
      val node = Node.fromPickle(lines.slice(ptr, ptr + numAhead).mkString("\n"))
      map = map + (node.id -> node)
      ptr += numAhead
    }
    new DecisionTree(map, numLabels)
  }
}
