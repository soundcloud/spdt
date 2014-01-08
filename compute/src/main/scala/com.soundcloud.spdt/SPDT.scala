package com.soundcloud.spdt

import System.{ currentTimeMillis => now }

import scala.collection.immutable.{ HashMap, HashSet }

import scala.io.BufferedSource
import scala.io.Source
import scala.concurrent.ExecutionContext.Implicits.global

import java.text.SimpleDateFormat

import breeze.linalg.{ DenseVector, Axis, DenseMatrix, sum, argmax, * }

import impurity._

import scala.concurrent._
import ExecutionContext.Implicits.global
import scala.concurrent.duration._

import java.util.concurrent.ConcurrentHashMap
import collection.JavaConverters._

import scala.collection._
import mutable.Builder
import generic.CanBuildFrom
import org.slf4j.LoggerFactory


case class SPDT(
  labels:              List[Int],
  impurityLimit:       Double,
  featureTypes:        Option[Map[Int,Feature.Type]] = None,
  errorWeight:         Option[Double] = Some(1.0),
  minFeatureFrequency: Option[Double] = None,
  minDelta:            Option[Double] = Some(0.0),
  tauMargin:           Double = 0.00,
  numWorkers:          Int = SPDT.numProcs,
  numBins:             Int = 100,
  val tree:            DecisionTree = new DecisionTree()) {

  import SPDT._
  import LinearAlgebra._

  val log = LoggerFactory.getLogger(this.getClass.getName)

  val (labelStringToIndexMap, _) = Data.makeClassMap(labels)

  val labelToIndexMap = labelStringToIndexMap
    .map(_._1.toInt)
    .zip(labelStringToIndexMap.map(_._2))
    .toMap

  val indexToLabel       = labelToIndexMap
    .map(_._2)
    .zip(labelToIndexMap.map(_._1))
    .toMap

  def labelToIndex(l: Int) = labelToIndexMap(l)
  def labelToIndex(s: String) = labelStringToIndexMap(s)

  val labelIndexes       = labels.map(labelToIndexMap(_))

  val numLabels = labels.length

  //// parallelTrain
  ////
  // Trains the decision tree on multiple treads.
  //
  // @param filename: file containing samples in LIBSVM format
  // @param maxIters: maximum number of iterations to run training for. If
  // left unspecified, training runs until all samples are perfectly labeled
  // or split cannot be found.
  //
  //  @returns new spdt instance incorporating new samples
  def parallelTrain(filename: String, maxIters: Option[Int]): SPDT = {
    var updateSPDT = this.copy()
    Data.parse(
      Source.fromFile(filename),
      classMap = Some(labelStringToIndexMap),
      preserveOrder = false){ (samples, chunkNo) => {

      if (updateSPDT.tree.numNodes != 1 && chunkNo != 0) {
        val pruned = updateSPDT.prune
        val prunedResults = samples.par.map(pruned.classify)
        val unprunedResults = samples.par.map(updateSPDT.classify)
        val trueLabels = samples.par.map(_.label)
        val unprunedMisses = trueLabels.zip(unprunedResults).count(t => t._1 != t._2)
        val prunedMisses   = trueLabels.zip(prunedResults).count(t => t._1 != t._2)
        if (prunedMisses < unprunedMisses) {
          updateSPDT = pruned
        }
      }

      updateSPDT = updateSPDT.parallelTrain(samples, maxIters)
    }}
    updateSPDT
  }

  //// parallelTrain
  ////
  // Trains the decision tree on multiple treads.
  //
  // @param samples: training samples
  // @param maxIters: maximum number of iterations to run training for. If
  // left unspecified, training runs until all samples are perfectly labeled
  // or split cannot be found.
  //
  //  @returns new spdt instance incorporating new samples
  def parallelTrain(samples: List[Sample], maxIters: Option[Int]) = {

    var iter = 0

    val groupSize = if (samples.length % numWorkers == 0) {
      samples.length / numWorkers
    } else {
      samples.length / numWorkers + 1
    }

    var decisionAdded = false
    var updateSPDT = this.copy()

    val totals = labelIndexes
      .par
      .map(l => l -> samples.count(_.label.get == l).toDouble)
      .toMap

    val freq = minFeatureFrequency.getOrElse(0.0)

    val featureFilter = {
      val countMaps =
        labelIndexes.map(l => l -> (new ConcurrentHashMap[Int,Double]).asScala).toMap

      samples.par.map(sample => {
        val labelMap = countMaps(sample.label.get)
        sample.features.keys.par.map(fId => {
          if (labelMap.contains(fId)) {
            val current = labelMap(fId)
            labelMap.replace(fId, current + 1)
          } else {
            labelMap.putIfAbsent(fId, 1.0)
          }
        })
      })

      labelIndexes.par.map(l =>
        HashSet(countMaps(l)
          .par
          .filter{case (k, v) => v / totals(l) >= freq}.keys.toList:_*))
        .reduce(_ ++ _)
    }

    do {
      val trainTic = now

      // assign the work
      val compressResults = samples
        .grouped(groupSize)
        .toList.par
        .map(grp => updateSPDT.compress(grp, iter, featureFilter))
        .flatten

      val updateMapFuture = Future {
        HashMap(compressResults.par.map(_._1.toVector).reduce(_ ++ _)
          .groupBy(_._1)
          .mapValues(updateTuples => updateTuples.map(_._2).reduce((l,r) => l.merge(r)))
          .toList:_*)
      }

      val labelCountsFuture = Future {
        HashMap(compressResults.par.map(_._2.toVector).reduce(_ ++ _).toList
          .groupBy(_._1)
          .mapValues(countTuples => countTuples.par.map(_._2).reduce(_ + _))
          .toList:_*)
      }

      val futures = for {
        um <- updateMapFuture
        lc <- labelCountsFuture
      } yield (um, lc)

      val (updateMap, labelCounts) = Await.result(futures, 180 seconds)

      logInfo(
        "operation" -> "parallel_train:compress",
        "elapsed_time" -> (now - trainTic),
        "sample_size" -> groupSize,
        "iteration" -> iter)

      val preNumNodes = updateSPDT.tree.numNodes

      if (updateMap.nonEmpty) {
        updateSPDT = updateSPDT.gather(updateMap, labelCounts, Some(iter))
      }

      decisionAdded = preNumNodes != updateSPDT.tree.numNodes

      iter += 1
    } while (decisionAdded && (maxIters.isEmpty || maxIters.get > iter))

    updateSPDT
  }

  //// train
  ////
  // Serial training method, for testing and small datasets
  //
  // @param samples: training samples
  // @param maxIters: maximum number of iterations to run training for. If
  // left unspecified, training runs until all samples are perfectly labeled
  // or split cannot be found.
  //
  //  @returns new spdt instance incorporating new samples
  def train(samples: List[Sample], maxIters: Option[Int] = None) = {
    require(samples.nonEmpty)
    var iter = 0
    var compressed = HashMap[Key,Histogram]()
    var decisionAdded = true
    var updateSPDT = this.copy()

    do {
      val Some((histograms, counts)) = updateSPDT.compress(samples, iter)
      val preNumNodes = updateSPDT.tree.numNodes
      updateSPDT = updateSPDT.gather(histograms, counts, Some(iter))
      decisionAdded = preNumNodes != updateSPDT.tree.numNodes
      iter += 1
    } while (decisionAdded && (maxIters.isEmpty || maxIters.get > iter))

    updateSPDT
  }

  //// classify
  ////
  // return the current classification of a given sample
  //
  // @param sample: the sample to classify
  //
  // @returns: a label if the tree can classify the sample
  def classify(sample: Sample): Option[Int] = tree.classify(sample)


  //// compress
  ////
  // Compress migrates samples to leaf nodes and then builds histograms for
  // each class and feature at that node.
  //
  // Training data is divided into groups and this process is run in parallel.
  //
  // @param samples: the training samples
  // @param iteration: the number of iterations the training has run for
  // @param featureFilter: features that are underrepresented and should be
  // filtered out
  //
  // @returns histograms for each (leafNode,feature,class) and class counts
  // for each (leaf)
  def compress(
    samples: List[Sample],
    iteration: Int,
    featureFilter: HashSet[Int] = HashSet()): Option[(HashMap[Key,Histogram], HashMap[Int,DenseVector[Double]])] = {

    require(samples.nonEmpty)

    val compressTic = now

    val isNewData = iteration == 0

    val noFeatureFilter = featureFilter.isEmpty

    // run the samples through the tree and obtain trace info
    val traces = samples.par.map(sample => {
      var nodeFeatureTrace = List[Int]()
      def trace(n: Node) { nodeFeatureTrace = n.feature.get :: nodeFeatureTrace }
      val nodeId = tree.traverse(sample, trace)

      if ((tree.graph(nodeId).isEmpty || isNewData)) {
        val keyMap = sample.features.par.map{case (featureId, value) => {
          if (featureFilter.contains(featureId) || featureFilter.isEmpty) {
            val key = Key(nodeId, featureId, sample.label.get)
            Some(key -> value)
          } else {
            None
          }
        }}.flatten.toVector

        if (keyMap.nonEmpty) {
          Some((
            nodeId -> HashSet(nodeFeatureTrace:_*),
            nodeId -> indicator(numLabels, Some(sample.label.get)),
            keyMap,
            sample))
        } else {
          None
        }
      } else {
        None
      }
    }).toList
      .flatten

    if (traces.isEmpty) return None

    val keyMapFuture = Future { traces.par.map(_._3).flatten.groupBy(_._1) }

    val nodeFeaturesFuture   = Future {
      HashMap(traces.par.map(_._1).toList:_*)
    }

    val nodeLabelCountFuture = Future {
      HashMap(traces.par
        .map(t => t._2)
        .groupBy(_._1)
        .mapValues(traceTuples => traceTuples.par.map(_._2).reduce(_ + _))
        .toList:_*)
    }

    val labelCountsFuture    = Future {
      traces.par
        .map(_._2._2)
        .reduce(_ + _)
    }

    val futures = for {
      nf <- nodeFeaturesFuture
      nl <- nodeLabelCountFuture
      lc <- labelCountsFuture
      km <- keyMapFuture } yield(nf, nl, lc, km)

    val (nodeFeatures, nodeLabelCount, labelCounts, keyMap) =
      Await.result(futures, 180 seconds)

    val histograms = HashMap(keyMap.par
      .map{case (key, valuesTuples) => {
        val Key(nodeId, featureId, label) = key
        val values = valuesTuples.map(_._2).toList

        key -> histogram(
          featureId,
          nodeId,
          label,
          values,
          nodeLabelCount,
          nodeFeatures)
      }}.toList:_*)

    Some((histograms, nodeLabelCount))
  }

  //// gather
  ////
  // Merges compress worker histograms and add new branches to decision tree.
  //
  // @param histograms: worker histograms
  // @param nodeLabelCount: class counts for each leaf
  // @param iter: the number of the iteration
  //
  // @return: a new spdt instance incorporating the new histograms
  def gather(
    histograms: HashMap[Key,Histogram],
    nodeLabelCount: HashMap[Int,DenseVector[Double]],
    iter: Option[Int] = None): SPDT = {

    val mergeTic = now

    // changes must be seen as one step
    var treeUpdate = tree

    // all changes to the tree must be seen as one step
    val numLeaves  = treeUpdate.leaves.length
    var decision = false

    val leafIds = treeUpdate.leaves.map(_.id).toList

    // load old counts, totals and update
    val updateLabelCounts = DenseMatrix(
      leafIds.map(id => if (nodeLabelCount.contains(id)) {
        nodeLabelCount(id).toArray
      } else {
        DenseVector.zeros[Double](numLabels).toArray
      }):_*)

    // load old counts, totals
    val oldNodeLabelCounts = DenseMatrix(treeUpdate.leaves.map(_.labelCounts.toArray):_*)

    val counts = oldNodeLabelCounts + updateLabelCounts
    val totals = sum(counts(*,::)).toDenseVector

    val numUpdates = totals.length

    treeUpdate = treeUpdate.updateHistograms(
      histograms,
      nodeLabelCount,
      isRealValued)

    logInfo(
      "operation" -> "gather:merge",
      "time_elapsed" -> (now - mergeTic),
      "iteration" -> iter.getOrElse("single"))

    val decisions = 0.until(numUpdates).par.map(v => {

      val leafTic = now

      // generate the leafImpurity
      val vClassProbabilities = counts(v,::).t.toArray.map(count =>
        if (totals(v) == 0) 0.0 else count / totals(v)).toList

      val leafImpurity = entropy(vClassProbabilities)
      val isPure = vClassProbabilities.contains(1.0)
      val leafNonEmpty = treeUpdate.leaves(v).histograms.nonEmpty

      if (!isPure && leafImpurity > impurityLimit && leafNonEmpty) {
        // merge histograms across classes
        val viHists = HashMap(treeUpdate.leaves(v).histograms
          .groupBy(_._1.unapply(0))
          .mapValues(histTuples => histTuples.par.map(_._2).reduce((l,r) => l.merge(r)))
          .toList:_*)

        val splitStats = viHists.par.map{case (featureId, viHist) => {
          val histUs = viHist.uniform

          // skip features that don't have
          //  1. a viable split
          //  2. the feature (e.g. boolean variables all false for this node)
          if (histUs.isEmpty || viHists.get(featureId).isEmpty) {
            None
          } else {
            val leaf = treeUpdate.leaves(v)

            // estimate delta for each candidate split
            val uStats = histUs.zipWithIndex.par.map{case (u, ind) => {
              val (lCount, rCount, lClassProbs, rClassProbs, tau) =
                splitClassProbabilities(leaf, featureId, u)

              val withinClassSplit = lClassProbs.toArray.toList
                .zipWithIndex
                .map{case (v, j) => v + rClassProbs(j)}
                .indexOf(2.0) != -1

              if (tau >= (1.0 - tauMargin) || tau <= (0.0 + tauMargin) || withinClassSplit) {
                None
              } else {
                val lImpurity = entropy(lClassProbs)
                val rImpurity = entropy(rClassProbs)

                val delta = leafImpurity - tau * lImpurity - (1.0 - tau) * rImpurity

                if (delta <= minDelta.getOrElse(0.0)) {
                  None
                } else {
                  val lClass = argmax(lClassProbs)
                  val rClass = argmax(rClassProbs)

                  Some((delta, tau, featureId, u, lCount, rCount, lClassProbs,
                    rClassProbs, lClass, rClass, ind, histUs.length,
                    leaf.id, leafImpurity, now - leafTic))
                }
              }
            }}.flatten.toList
            if (uStats.nonEmpty) Some(uStats.sortBy(_._1).last) else None
          }
        }}.toList.flatten.sortBy(_._1)

        if (splitStats.nonEmpty) {
          Some(splitStats.last)
        } else {
          None
        }
      } else {
        None
      }
    }).toList
      .flatten

    decisions.foreach{case (delta, tau, featureId, u, lCount, rCount,
      lClassProbs, rClassProbs, lClass, rClass, uInd, len, parentId,
      leafImpurity, leafTime) => {

      logInfo(
        "operation" -> "gather:add_decision",
        "time_elapsed" -> leafTime,
        "iteration" -> iter.getOrElse("single"),
        "node" -> parentId,
        "node_impurity" -> leafImpurity,
        "delta" -> delta,
        "tau" -> tau,
        "feature" -> featureId,
        "u" -> u,
        "u_ind" -> "%d/%d".format(uInd + 1,len),
        "left_count" -> lCount,
        "right_count" -> rCount,
        "left_class_probs" -> lClassProbs.toArray.toList,
        "right_class_probs" -> rClassProbs.toArray.toList,
        "left_class" -> indexToLabel(lClass),
        "right_class" -> indexToLabel(rClass))

      treeUpdate = treeUpdate.addDecision(parentId, featureId, u, lClass, rClass)
      decision = true
    }}

    this.copy(tree = treeUpdate)
  }

  //// isRealValued
  ////
  // Indicates whether a given feature is real-valued or not. This is used to
  // determine whether to represent the feature as a real-valued histogram or
  // a boolean histogram.
  //
  // @param featureId: the id of the feature in question
  //
  // @returns: true if the feature has real-valued, false otherwise
  def isRealValued(featureId: Int) = {
    if (featureTypes.isDefined) {
      featureTypes.get.get(featureId) == Some(Feature.RealValued)
    } else {
      true
    }
  }

  //// splitClassProbabilities
  ////
  // Calculates the probabilities of each class on either side of a binary
  // decision.
  //
  // @param leaf: the node for which a decision is potentially being added
  // @param featureId: the feature that is to be split
  // @param u: the point at which that feature will be split
  //
  // @returns: the total on each side, the probabilities of each class on
  // on each side, and the ratio of samples going left
  def splitClassProbabilities(
    leaf: Node,
    featureId: Int,
    u: Double): (Double, Double, DenseVector[Double], DenseVector[Double], Double) = {

    val relevantHistograms = leaf.featureHistograms(featureId, labels)

    val (lCounts, rCounts, lTotal, rTotal) = relevantHistograms.par
      .map{case (key, hist) => {
        val Key(i, j) = key
        val lAry = indicator(numLabels)
        val rAry = indicator(numLabels)

        val lC = hist.sum(u)
        val rC = hist.total - lC
        lAry(j) = lC
        rAry(j) = rC
        (lAry, rAry, lC, rC)
      }}.reduce((lEle, rEle) => (
        lEle._1 + rEle._1,
        lEle._2 + rEle._2,
        lEle._3 + rEle._3,
        lEle._4 + rEle._4))

    return (
      lTotal,
      rTotal,
      if (lTotal > 0) lCounts / lTotal else indicator(numLabels),
      if (rTotal > 0) rCounts / rTotal else indicator(numLabels),
      lTotal / (lTotal + rTotal))
  }

  //// histogram
  ////
  // Generate the appropriate histogram for a given feature. This is tricky
  // because we don't want to store all non-present boolean features. In a
  // data set featurized with the hashing set, this number could be huge!
  //
  // @param featureId: the id of the feature
  // @param nodeId: the id of the node
  // @param label: the class label
  // @param values: the values to be incorporated
  // @param nodeLabelCount: the count of each class at that node
  // @param nodeFeatures: the features present at that node
  //
  // @returns: the histogram for that node
  def histogram(
    featureId: Int,
    nodeId: Int,
    label: Int,
    values: List[Double],
    nodeLabelCount: HashMap[Int,DenseVector[Double]],
    nodeFeatures: HashMap[Int,HashSet[Int]]) =
    if (isRealValued(featureId)) {
      var h = new RealValuedHistogram(numBins)
      values.foreach(value => {
        val newH = h.update(value)
        h = newH
      })
      h
    } else {
      val numTrue = values.length
      val numFalse = if (nodeFeatures(nodeId).contains(featureId)) {
        0
      } else {
        nodeLabelCount(nodeId)(label) - numTrue
      }
      new BooleanHistogram(numFalse, numTrue)
    }


  //// evaluation methods
  ////

  //// eval
  ////
  // Generates confusion statistics.
  //
  // @param filename: the path to a file containing samples in LIBSVM format
  //
  // @return: a list of confusions statistics
  def eval(filename: String): List[Double] = {
    if (labels != List(0, 1))
      throw new IllegalArgumentException("evaluation not implemented for more than two classes!")
    Data.parse(
      Source.fromFile(filename),
      classMap = Some(labelStringToIndexMap),
      preserveOrder = false){ (samples, chunkNo) =>

      eval(samples)
    }.reduce((s: List[Double], a: List[Double]) => s.zip(a).map(t => t._1 + t._2))
  }

  //// eval
  ////
  // Generates confusion statistics.
  //
  // @param samples: a list of labeled samples
  //
  // @return: a list of confusions statistics
  def eval(samples: List[Sample]): List[Double] = {
    require(labels == List(0, 1))

    // if the tree is untrained we assume that the tree guesses all negative
    if (tree.numNodes == 1)
      return List(0, samples.count(_.label.get == 0), 0, samples.count(_.label.get == 1))

    samples.map(e => {
      val prediction = classify(e).get
      val isTP = e.label.get == 1 && prediction == 1
      val isTN = e.label.get == 0 && prediction == 0
      val isFP = prediction == 1 && e.label.get == 0
      val isFN = prediction == 0 && e.label.get == 1
      List(isTP, isTN, isFP, isFN).map(if (_) 1.0 else 0.0)})
        .reduce((s: List[Double], a: List[Double]) => s.zip(a).map(t => t._1 + t._2))
  }

  //// pruning
  ////
  // Prune the tree based on the MDL pruning algorithm.
  //
  // For more information see:
  // http://www.almaden.ibm.com/cs/projects/iis/hdb/Publications/papers/kdd95_mdl.pdf
  //
  // @returns: the pruned tree
  def prune: SPDT = {
    if (tree.numNodes == 1) return this

    var treeUpdate = tree

    val origNumNodes = tree.numNodes
    val pruneTic = now

    def traverse(n: Node): Unit = {
      val (cst, doPrune) = cost(treeUpdate, n)

      // prune if necessary
      if (doPrune) {
        treeUpdate = treeUpdate.pruneChildrenOf(n.id)
      }

      // traverse up into the tree
      val p = treeUpdate.parent(n)
      if (p.isDefined) traverse(p.get)
    }

    val leaves = treeUpdate.leaves.zip(treeUpdate.leaves.map(_.id))

    leaves.foreach{case (leaf, id) => if (treeUpdate.exists(id)) traverse(leaf)}

    logInfo(
      "operation" -> "prune",
      "time_elapsed" -> (now - pruneTic),
      "original_num_nodes" -> origNumNodes,
      "pruned_num_nodes" -> treeUpdate.numNodes)

    this.copy(tree = treeUpdate)
  }

  //// cost
  ////
  // Calculate the MDL cost of a subtree below a node.
  //
  // @param treeUpdate: the tree containing the subtree
  // @param node: the node to start with
  //
  // @returns: the subtree cost and whether the node cost < the subtree cost
  def cost(treeUpdate: DecisionTree, n: Node): (Double,Boolean) = {
    val lThresh = 1.0
    val m = treeUpdate.leaves.length.toDouble
    val pt0 = 1.0 / (1.0 + 1.0 / (m - 1.0))
    val pt1 = 1 - pt0

    // determine cost
    val nodeCost = - scala.math.log(pt0) + error(n)
    if (!n.isLeaf) {
      val l = treeUpdate.left(n).get
      val r = treeUpdate.right(n).get
      val childCost = cost(treeUpdate, l)._1 + cost(treeUpdate, r)._1
      val subTreeCost = - scala.math.log(pt1) + lThresh + childCost
      (List(nodeCost, subTreeCost).min, nodeCost < subTreeCost)
    } else {
      (nodeCost, false)
    }
  }


  //// error
  ////
  // Calculates the error at a node. This error function assumes that each
  // error is simply worth 1 bit.
  //
  // @param n: the node to calculate error for
  //
  // @returns: the error at that node
  def error(n: Node): Double = {
    n.labelCounts.toArray.toList.zipWithIndex.map{case (count, j) => {
      if (n.label.isDefined && j == n.label.get) 0.0 else count
    }}.sum * errorWeight.getOrElse(1.0)
  }


  //// logging
  ////
  private def logInfo(m: (String,Any)*) =
    log.info(LogFormat.mapToJson(m.toMap))


  //// requirements
  ////
  minFeatureFrequency match {
    case Some(thresh) => require(0.0 <= thresh && thresh <= 1.0)
    case None => {}
  }

  require(numLabels == tree.numLabels)
}


object SPDT extends Pickling[SPDT] {

  protected val runtime = Runtime.getRuntime()

  val numProcs = runtime.availableProcessors()

  Threads.setParallelismGlobally(numProcs)

  def stats(tp: Double, tn: Double, fp: Double, fn: Double) = {
    val acc = (tp + tn) / ( tp + tn + fp + fn )
    val tpr = tp / ( tp + fn )
    val pre = tp / ( tp + fp )
    val fpr = fp / ( fp + tn )
    (acc, tpr, pre, fpr)
  }

  protected val fullFormat = "(.*)_(.*)_(.*).spdt".r
  protected val partialFormat = "(.*).spdt".r
  protected val dateFormat = "yyyy-MM-dd_HHmmss"

  // file format generates a path for a new model using the current date
  def fileFormat(path: String): String = {
    val dateformatter = new SimpleDateFormat(dateFormat)
    val fullPath = path.split('/')
    val dir = if (fullPath.length == 1) {
      "."
    } else {
      fullPath.slice(0, fullPath.length - 1).mkString("/")
    }
    val filename = fullPath.last

    val dateSuffix = dateformatter.format(new java.util.Date())

    val fullMatch = fullFormat.findAllIn(filename).matchData.toList
    val partialMatch = partialFormat.findAllIn(filename).matchData.toList

    dir + "/" + (if (fullMatch.nonEmpty) {
      fullMatch(0).group(1)
    } else if (partialMatch.nonEmpty) {
      partialMatch(0).group(1)
    } else {
      filename
    }) + "_" + dateSuffix + ".spdt"
  }

  // serialize an spdt model
  def pickle(spdt: SPDT): String = {
    val typeMap = Map(Feature.Boolean -> "bool", Feature.RealValued -> "real")
    val featureMapString = if (spdt.featureTypes.isEmpty) {
      "_"
    } else if (spdt.featureTypes.get.isEmpty) {
      "empty"
    } else {
      spdt.featureTypes.get
        .map{case (i, t) => "%d-%s".format(i, typeMap(t))}
        .mkString(",")
    }

    "spdt|%s\n%s".format(
      List(
        lstToA(spdt.labels),
        Formatting.formatDecimal(spdt.impurityLimit),
        featureMapString,
        doubleOptToA(spdt.errorWeight),
        doubleOptToA(spdt.minFeatureFrequency),
        doubleOptToA(spdt.minDelta),
        Formatting.formatDecimal(spdt.tauMargin),
        spdt.numBins.toString,
        triggerReadEles(1)).mkString(":"),
      DecisionTree.pickle(spdt.tree))
  }

  // deserialize a model
  def fromPickle(s: String): SPDT = {
    val lines = s.split("\n")
    val Array(tag, classVarStr) = lines.head.split('|')
    require(tag == "spdt")

    val classVars = classVarStr.split(":")
    val revTypeMap = Map("bool" -> Feature.Boolean, "real" -> Feature.RealValued)
    val featureMap = if (classVars(2) == "_") {
      None
    } else if (classVars(2) == "empty") {
      Some(Map[Int,Feature.Type]())
    } else {
      Some(
        classVars(2).split(",").map(tup => {
          val Array(featureId, typ) = tup.split('-')
          featureId.toInt -> revTypeMap(typ)
        }).toMap)
    }

    new SPDT(
      labels              = aToLst[Int](classVars(0)),
      impurityLimit       = classVars(1).toDouble,
      featureTypes        = featureMap,
      errorWeight         = aToOpt[Double](classVars(3)),
      minFeatureFrequency = aToOpt[Double](classVars(4)),
      minDelta            = aToOpt[Double](classVars(5)),
      tauMargin           = classVars(6).toDouble,
      numBins             = classVars(7).toInt,
      tree                = DecisionTree.fromPickle(lines.tail.mkString("\n")))
  }

}
