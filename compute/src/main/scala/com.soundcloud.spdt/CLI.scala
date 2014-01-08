package com.soundcloud.spdt

import org.rogach.scallop._
import scala.io.Source

import java.text.SimpleDateFormat

import org.slf4j.LoggerFactory


object CLI {

  val log = LoggerFactory.getLogger(this.getClass.getName)

  def main(args: Array[String]) {
    val opts = new ScallopConf(args.toList.tail) {

      banner("""Usage: spdt [OPTIONS]...
                |Streaming parallel decision trees for classification.
                |Options:
                |""".stripMargin)

      footer("""|
                |© 2014 Trust Safety & Security Team | SoundCloud Ltd.
                |Contact: security@soundcloud.com""".stripMargin)

      val trainingData = opt[String](
        "training-data",
        'd',
        "training data file path")

      val evalData = opt[String](
        "test-data",
        't',
        "test data file path")

      val modelPath = opt[String](
        "model-path",
        'm',
        "location to save the pickled model", required=true)

      val labels = opt[String](
        "class-labels",
        'c',
        "observable class labels, 'int1,int2,...' (default 0,1)")

      val booleanFeatures = opt[Boolean](
        "boolean-features",
        'b',
        "this flag enables boolean features")

      val realValuedFeatures = opt[String](
        "real-features",
        'r',
        "indexes of real-valued features 'realTypeInt1,realTypeInt2,...'")

      val errorWeight = opt[Double](
        "error-weight",
        'e',
        "error weight in MDL pruning (default 1.0)")

      val minDelta = opt[Double](
        "min-delta",
        's',
        "minimum delta value for a split to be generated (default x > 0.0)")

      val impurityLimit = opt[Double](
        "impurity-limit",
        'i',
        "maximum impurity permitted limit at leaf nodes (default 0.0)")

      val minFeatureFreq = opt[Double](
        "min-feature-frequency",
        'f',
        "minimum feature frequency when constructing histograms (default None)")

      val numBins = opt[Int](
        "num-bins",
        'n',
        "number of histogram bins (default 100)")

      val iterations = opt[Int](
        "training-iterations",
        'l',
        "limit on number of iterations for training to converge (default 10)")

      val predict = opt[String](
        "predict",
        'p',
        "file of samples to predict")

      val print = opt[String](
        "print",
        'v',
        "file with tsv feature map for generating a dotfile, format: <hashval>\t<feature_label>")
    }

    val doTrain = opts.trainingData.isDefined
    val doPrune = opts.errorWeight.isDefined
    val doTest  = opts.evalData.isDefined
    val doPrint = opts.print.isDefined
    val doPredict = opts.predict.isDefined
    val fileExists = exists(opts.modelPath())
    val isUpdate = if (doTrain || doPrune) true else false

    val featureTypes: Option[Map[Int,Feature.Type]] =
      if (opts.realValuedFeatures.isDefined) {
        Some(opts.realValuedFeatures().split(',').map(_.toInt -> Feature.RealValued).toMap)
      } else if (opts.booleanFeatures()) {
        Some(Map[Int,Feature.Type]())
      } else {
        None
      }

    val labels =
      if (opts.labels.isDefined) opts.labels().split(',').map(_.toInt).toList else List(0, 1)

    var spdt = if (fileExists) {
      log.info("Loading model: " + opts.modelPath())
      val source = scala.io.Source.fromFile(opts.modelPath())
      val pickle = source.mkString
      source.close
      SPDT.fromPickle(pickle)
    } else {
      log.info("Creating new model.")
      new SPDT(
        numBins = opts.numBins.get.getOrElse(100),
        labels = labels,
        impurityLimit = opts.impurityLimit.get.getOrElse(0.0),
        featureTypes = featureTypes,
        minFeatureFrequency = opts.minFeatureFreq.get,
        minDelta = Some(opts.minDelta.get.getOrElse(0.0)),
        errorWeight = opts.errorWeight.get,
        tree = new DecisionTree(labels.length))
    }

    if (doTrain) {
      spdt = spdt.parallelTrain(
        opts.trainingData(),
        Some(opts.iterations.get.getOrElse(10)))
    }

    val preprunedStats: Option[List[Double]] = if (doTest) {
      val result = spdt.eval(opts.evalData())
      if (result.nonEmpty) {
        val List(bTP, bTN, bFP, bFN) = result
        val (beforeAcc, beforeTpr, beforePre, beforeFpr) =
          SPDT.stats(bTP, bTN, bFP, bFN)
        Some(List(bTP, bTN, bFP, bFN, beforeAcc, beforeTpr, beforePre, beforeFpr))
      } else {
        log.info("No evalutation results! Could be that you have an empty decision tree.")
        None
      }
    } else {
      None
    }

    val completedTest = preprunedStats.isDefined

    val prunedStats: Option[List[Double]] = if (doPrune) {
      val pruned = spdt.prune

      if (doTest && completedTest) {
        val result = spdt.eval(opts.evalData())
        if (result.nonEmpty) {
          val List(aTP, aTN, aFP, aFN) = result
          val (afterAcc, afterTpr, afterPre, afterFpr) =
            SPDT.stats(aTP, aTN, aFP, aFN)

          if (afterAcc >= preprunedStats.get.apply(4)) {
            spdt = pruned
            log.info("pruned tree outperforms unpruned. adopting pruned tree.")
          } else {
            log.info("unpruned tree outperforms pruned.")
          }
          Some(List(aTP, aTN, aFP, aFN, afterAcc, afterTpr, afterPre, afterFpr))
        } else {
          log.info("No evalutation results! Could be that you have an empty decision tree.")
          None
        }
      } else {
        spdt = pruned
        None
      }
    } else {
      None
    }

    if (doTest) {
      val (which, stats) =
        if (prunedStats.isEmpty || preprunedStats.get.apply(4) > prunedStats.get.apply(4)) {
          ("pre-pruned", preprunedStats.get)
        } else {
          ("post-pruned", prunedStats.get)
        }
      report(
        which,
        stats(0), stats(1), stats(2), stats(3), stats(4), stats(5), stats(6), stats(7))
    }

    if (doPredict) {
      val outputFile = "./spdt_predictions.txt"
      val writer = new java.io.PrintWriter(new java.io.File(outputFile))
      val (classMap, respMap) = Data.makeClassMap(labels)

      Data.parse(
        scala.io.Source.fromFile(opts.predict()),
        classMap = Some(classMap)) { (samples, chunkNo) =>
        samples.foreach{sample =>
          writer.write(respMap(spdt.classify(sample).get) + "\n")}
      }
      writer.close()
      log.info(s"prediction results written to $outputFile.")
    }

    if (isUpdate) {
      val outputFilepath = SPDT.fileFormat(opts.modelPath())
      val writer = new java.io.PrintWriter(new java.io.File(outputFilepath))
      writer.write(SPDT.pickle(spdt))
      writer.close()
      log.info("model written to " + outputFilepath)
    }

    if (doPrint) {
      spdt.tree.dotfile("spdt.dot", opts.print.get)
      log.info("dotfile written to ./spdt.dot")
    }
  }

  def report(
    name: String,
    tp: Double,
    tn: Double,
    fp: Double,
    fn: Double,
    acc: Double,
    tpr: Double,
    pre: Double,
    fpr: Double) {
    log.info(name + " TPs=%d, TNs=%d, FPs=%d, FNs=%d".format(tp.toInt, tn.toInt, fp.toInt, fn.toInt))
    log.info(name + " ACC\t" + acc)
    log.info(name + " REC\t" + tpr)
    log.info(name + " PRE\t" + pre)
    log.info(name + " FPR\t" + fpr)
  }

  def exists(path: String) = {
    val f = new java.io.File(path)
    f.exists()
  }
}

