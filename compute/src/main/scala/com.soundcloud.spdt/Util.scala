package com.soundcloud.spdt

import java.text.SimpleDateFormat

import java.text.DecimalFormat
import java.math.BigDecimal

import breeze.linalg.DenseVector
import org.slf4j.LoggerFactory


object LinearAlgebra {
  // makes a simple indicator vector with one non-zero element
  def indicator(len: Int, i: Option[Int] = None, count: Double = 1.0) = {
    val d = DenseVector.zeros[Double](len)
    if (i.isDefined) {
      d(i.get) = count
    }
    d
  }
}


object Formatting {
  val df = new DecimalFormat("0.0#########")

  // eliminates trailing zeros in printing a decimal
  def formatDecimal(d: Double) = df.format((new BigDecimal(d.toString)).stripTrailingZeros())
}


object LogFormat {

  def mapToJson[T](m: Map[String,T]): String = mapToJson(m.toSeq:_*)

  def mapToJson[T](kvPairs: (String,T)*): String = "{" +
    kvPairs.sortBy(_._1)
      .map(t => {
        val s2 = t._2 match {
          case s: String => "\"" + s + "\""
          case s: Seq[_] => "[" + s.map(_.toString).mkString(",") + "]"
          case any => any.toString
        }
        "\"%s\":%s".format(t._1, s2)
      })
      .mkString(",") +
    "}"
}


object Threads {

  val log = LoggerFactory.getLogger(this.getClass.getName)

  // control the parallelism of the JVM
  def setParallelismGlobally(numThreads: Int): Unit = {
    val parPkgObj = scala.collection.parallel.`package`
    val defaultTaskSupportField = parPkgObj.getClass.getDeclaredFields.find{
      _.getName == "defaultTaskSupport"
    }.get

    defaultTaskSupportField.setAccessible(true)
    defaultTaskSupportField.set(
      parPkgObj,
      new scala.collection.parallel.ForkJoinTaskSupport(
        new scala.concurrent.forkjoin.ForkJoinPool(numThreads)
      )
    )
    log.info(LogFormat.mapToJson(Map(
      "action" -> "set parallelism",
      "max" -> numThreads
    )))
  }
}
