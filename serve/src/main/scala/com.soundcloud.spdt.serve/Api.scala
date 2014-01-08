package com.soundcloud.spdt.serve

import scala.util.{ Success, Failure }

import scala.concurrent._
import ExecutionContext.Implicits.global

import org.scalatra._
import scalate.ScalateSupport

import java.util.Date

import com.soundcloud.spdt.LogFormat.{ mapToJson => jsonize, _ }
import com.soundcloud.spdt.{
  SPDT,
  Sample,
  LogFormat
}

import java.util.concurrent.atomic.AtomicReference

import io.prometheus.client.Gauge
import org.slf4j.LoggerFactory

import io.prometheus.client._

/**
 * Web servlet provides routing for SafeBrowsing Lister API.
 */
class ApiServlet(
  modelPath: String,
  hdfs: HDFSAccess,
  spdtSeed: SPDT,
  saveSnapshots: Boolean = false)
  extends ScalatraServlet
    with ScalateSupport
    with MethodOverride {

  val spdt = new AtomicReference(spdtSeed)
  val log = LoggerFactory.getLogger(this.getClass.getName)

  post("/classify") {
    try {
      val json: String = new String(request.body)
      val sample = Sample.fromJson(json)

      val label = spdt.get.classify(sample).getOrElse("unknown")

      val logMap = Map("endpoint" -> "/classify", "label" -> label)

      logInfo(logMap + ("status" -> 200, "sample" -> sample.toString))

      meter("classify", 200.toString)

      Ok(jsonize(logMap))
    } catch {
      case e: Throwable => {
        val msg = "/classify request failed for input: %s"
          .format(request.body.slice(0, 1024))
        log.error(msg, e)
        InternalServerError(msg)
      }
    }
  }


  // TODO: Make this variable thread safe... assuming people will use this
  // endpoint on a sane schedule for now.
  private var updateId = 0

  post("/update") {
    try {
      val json: String = new String(request.body)
      val samples = scala.util.Random.shuffle(Sample.listFromJson(json))

      val trainFuture = Future {
        // TODO: develop a better evaluation strategy.
        var spdtSnap = spdt.get
        var spdtUpdate = spdt.get
        val numEval = samples.length / 10

        val trainSet = samples.take(samples.length - numEval)
        val testSet = samples.takeRight(numEval)

        spdtUpdate = if (trainSet.length < 10000) {
          spdtUpdate.train(trainSet, Some(10))
        } else {
          spdtUpdate.parallelTrain(trainSet, Some(10))
        }

        val spdtUpdatePruned = spdtUpdate.prune

        // seed stats estimate the performance of the original classifier
        val List(seedtp, seedtn, seedfp, seedfn) = spdtSeed.eval(samples)
        val (seedacc, seedtpr, seedpre, seedfpr) = SPDT.stats(seedtp, seedtn, seedfp, seedfn)

        report("seed", spdtSeed.tree.numNodes, seedacc, seedtpr, seedpre, seedfpr)

        // lookahead stats estimate the performance of the current classifier
        val List(tp, tn, fp, fn) = spdtSnap.eval(testSet)
        val (acc, tpr, pre, fpr) = SPDT.stats(tp, tn, fp, fn)

        report("lookahead", spdtSnap.tree.numNodes, acc, tpr, pre, fpr)

        // stats on the unpruned tree
        val List(pretp, pretn, prefp, prefn) = spdtUpdate.eval(testSet)
        val (preacc, pretpr, prepre, prefpr) = SPDT.stats(pretp, pretn, prefp, prefn)

        report("preprune", spdtUpdate.tree.numNodes, preacc, pretpr, prepre, prefpr)

        // stats on the pruned tree
        val List(psttp, psttn, pstfp, pstfn) = spdtUpdatePruned.eval(testSet)
        val (pstacc, psttpr, pstpre, pstfpr) = SPDT.stats(psttp, psttn, pstfp, pstfn)

        report("pstprune", spdtUpdatePruned.tree.numNodes, pstacc, psttpr, pstpre, pstfpr)

        if (seedacc > preacc && seedacc > pstacc) {
          updateId = 0
          (
            "seed",
            spdtSnap,
            spdtSeed,
            List(seedtp, seedtn, seedfp, seedfn, seedacc, seedtpr, seedpre, seedfpr))
        } else if (pstacc >= preacc) {
          updateId += 1
          (
            "pruned",
            spdtSnap,
            spdtUpdatePruned,
            List(psttp, psttn, pstfp, pstfn, pstacc, psttpr, pstpre, pstfpr))
        } else {
          updateId += 1
          (
            "unpruned",
            spdtSnap,
            spdtUpdate,
            List(pretp, pretn, prefp, prefn, preacc, pretpr, prepre, prefpr))
        }
      }

      val logMap = Map(
        "endpoint" -> "/update",
        "updateId" -> updateId,
        "num_samples" -> samples.length)

      trainFuture onComplete {
        case Success(s) => {
          val wasPruned = s._1
          val spdtSnap = s._2
          val spdtUpdate = s._3
          val List(tp, tn, fp, fn, acc, tpr, pre, fpr) = s._4

          if (!spdt.compareAndSet(spdtSnap, spdtUpdate)) {
            meter("update", "failure")
            throw new java.lang.RuntimeException("SPDT instance mutated during update!")
          } else {
            logInfo(logMap + (
              "status" -> "success",
              "pruned" -> wasPruned,
              "confusion_tp,tn,fp,fn" -> "%d,%d,%d,%d".format(
                tp.toInt, tn.toInt, fp.toInt, fn.toInt),
              "ACC" -> acc,
              "TPR" -> tpr,
              "PRE" -> pre,
              "FPR" -> fpr)
            )
            meter("update", "success")

            report("train", spdtUpdate.tree.numNodes, acc, tpr, pre, fpr)
            gauge("train", "property", "update_number", updateId.toDouble)

            if (saveSnapshots) {
              val updatePath = SPDT.fileFormat(modelPath)
              hdfs.create(updatePath, SPDT.pickle(spdtUpdate))
            }
          }
        }
        case Failure(e) => {
          log.error("SPDT update failed!", e)
          meter("update", "failure")
        }
      }

      logInfo(logMap + ("status" -> 202))

      // TODO: meter failures when more status codes are put in
      meter("update", 202.toString)

      Accepted(jsonize(logMap))
    } catch {
      case e: Throwable => {
        val msg = "/update request failed for input: %s"
          .format(request.body.slice(0, 1024))
        log.error(msg, e)
        InternalServerError(msg)
      }
    }
  }

  def report(stage: String, numNodes: Int, acc: Double, tpr: Double, pre: Double, fpr: Double) = {

    gauge(stage, "property", "num_nodes", numNodes.toDouble)
    gauge(stage, "statistic", "accuracy", acc)
    gauge(stage, "statistic", "recall", tpr)
    gauge(stage, "statistic", "precision", pre)
    gauge(stage, "statistic", "false_positive_rate", fpr)

    (numNodes, acc, tpr, pre, fpr)
  }


  get("/ping") {
    "And a good day to you too, Sir!"
  }

  notFound {
    findTemplate(requestPath) map { path =>
      contentType = "text/html"
      layoutTemplate(path)
    } orElse serveStaticResource() getOrElse resourceNotFound()
  }

  private def logInfo(m: Map[String,Any]) =
    log.info(LogFormat.mapToJson(m))

  def metricsTypeName = "requests"
  def metricsSubtypeName: Option[String] = Some("serve")

  val counter: Counter = Counter.build()
    .namespace("spdt")
    .name("api_requests_total")
    .labelNames("endpoint", "status")
    .help("SPDT API counters.")
    .register()

  def meter(endpoint: String, status: String) {
    counter.labels(endpoint,status).inc()
  }

  private val nodeGauge: Gauge = Gauge.build()
    .namespace("spdt")
    .name("api_serve_guage")
    .labelNames("stage", "type", "subtype")
    .help("SPDT API gauges.")
    .register()

  private def gauge(stage: String, typ: String, subtype: String, value: Double) = {
    nodeGauge.labels(stage, typ, subtype).set(value)
  }
}
