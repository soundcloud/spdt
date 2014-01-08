package com.soundcloud.spdt

//// key
////
// Used to represent sparse, multi-dimensional collections in a flat
// collection such as a HashMap
//
// @param eles: the index in each dimension:w
case class Key(eles: Int*) {
  val unapply = eles.toList
}


object Key extends Pickling[Key] {
  def pickle(k: Key) = "k|%s".format(k.unapply.mkString(":"))

  def fromPickle(pickled: String): Key = {
    val Array(tag, ints) = pickled.split('|')
    require(tag == "k")
    Key(ints.split(':').map(_.toInt).toList:_*)
  }
}
