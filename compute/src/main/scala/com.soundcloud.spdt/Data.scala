package com.soundcloud.spdt

import scala.collection.mutable.MutableList

import scala.io.BufferedSource
import org.slf4j.LoggerFactory


object Data {

  val log = LoggerFactory.getLogger(this.getClass.getName)

  protected val runtime = Runtime.getRuntime()
  import runtime.{ totalMemory, freeMemory, maxMemory }

  // for reading LIBSVM format map +1 and -1 to 1 and 0 labels respectively.
  def makeClassMap(labels: List[Int]) = if (labels.toSet == Set(0, 1)) {
    // binary classification forward mapping
    (Map("-1" -> 0, "0" -> 0, "1" -> 1, "+1" -> 1),
     Map(0 -> "-1", 1 -> "+1"))
  } else {
    val forwardMap = labels.sorted.zipWithIndex.map{case (l, i) => {
      if (l > 0) {
        List(("+" + l.toString) -> i, l.toString -> i)
      } else {
        List(l.toString -> i)
      }
    }}.flatten.toMap
    val backwardMap = labels.sorted.zipWithIndex.map{case (l, i) =>
      (i -> ((if (l > 0) "+" else "") + l.toString))}.toMap
    (forwardMap, backwardMap)
  }

  // read in LIBSVM samples in batches so as not to OOM
  def parse[T](
    source: BufferedSource,
    classMap: Option[Map[String,Int]],
    preserveOrder: Boolean = true)
    (action: (List[Sample],Int) => T): List[T] = {

    val libSvm = """(^[+-]?\d)((?:\s(?:\d*):(?:-?\d*(\.\d*)*+))+)""".r
    val lines = source.getLines()
    val results = MutableList[T]()
    var chunkNo = 0
    var linesRead = 0

    while (lines.hasNext) {
      val samples = MutableList[Sample]()
      var chunkLinesRead = 0

      while ((chunkLinesRead == 0 || chunkLinesRead % 250000 != 0) && lines.hasNext) {

        val line = lines.next
        val matches = libSvm.findAllIn(line).matchData.toList

        if (matches.nonEmpty) {
          val label = {
            val l = matches(0).group(1)

            if (classMap.isDefined) classMap.get.apply(l) else l.toInt
          }

          val features = matches(0).group(2)
            .split(' ')
            .tail
            .map(_.split(':'))
            .map(ary => (ary(0).toInt, ary(1).toDouble))
            .toMap

          Sample(features, Some(label)) +=: samples

          linesRead += 1
          chunkLinesRead += 1
        } else {
          log.error("Input format warning, malformed input: " + line)
        }
      }

      if (samples.length > 1) {
        logInfo(Map(
          "operation" -> "perform_action",
          "chunk_id" -> chunkNo,
          "lines_read" -> linesRead))

        if (preserveOrder) {
          action(samples.toList.reverse, chunkNo)
        } else {
          action(samples.toList, chunkNo)
        } +=: results

        chunkNo += 1
      }
    }
    source.close()
    results.toList
  }

  private def logInfo(m: Map[String,Any]) =
    log.info(LogFormat.mapToJson(m))
}
