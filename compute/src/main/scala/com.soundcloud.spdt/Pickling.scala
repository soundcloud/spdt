package com.soundcloud.spdt

import breeze.linalg.DenseVector


// trait and helpers for object serialization and deserialization
// TODO: use Scala pickling once it can handle deeply nested objects.
trait Pickling[T] {

  def pickle(picklee: T): String

  def fromPickle(pickled: String): T

  def optToA(o: Option[Any]) = if (o.isDefined) o.get.toString else "_"
  def doubleOptToA(o: Option[Double]) = if (o.isDefined) Formatting.formatDecimal(o.get) else "_"

  def aToOpt[T: Manifest](s: String): Option[T] =
    (if (s == "_") None else manifest[T] match {
      case Manifest.Int => Some(s.toInt)
      case Manifest.Double => Some(s.toDouble)
    }).asInstanceOf[Option[T]]

  def lstToA(l: List[Any]) = l.mkString(",")
  def vecToA(v: DenseVector[Double]) = lstToA(v.toArray.toList)

  def aToLst[T: Manifest](s: String): List[T] = {
    val split = s.split(',')
    ((manifest[T] match {
      case Manifest.Int => split.map(_.toInt)
      case Manifest.Double => split.map(_.toDouble)
    }).toList).asInstanceOf[List[T]]
  }

  def aToVec(s: String): DenseVector[Double] = {
    val l = aToLst[Double](s)
    DenseVector[Double](l:_*)
  }

  val readLinesRegex = "\\[(\\d*)\\]".r

  def triggerReadEles(count: Int) = "[" + count + "]"

  def readReadEles(s: String) =
    readLinesRegex.findAllIn(s).matchData.toList(0).group(1).toInt
}
