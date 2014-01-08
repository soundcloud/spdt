package com.soundcloud.spdt.serve

object FileSystem {
  abstract class Entry {
    val path: String
    val modifiedAt: Long
  }

  case class Folder(path: String, modifiedAt: Long) extends Entry
  case class File  (path: String, modifiedAt: Long, sizeInBytes: Long) extends Entry
}

abstract class FileSystem {

  import FileSystem._

  def ls(path: String): List[Entry]

  def rm(path: String): Boolean

  def create(path: String, body: Array[Byte]): Boolean

  def append(path: String, body: Array[Byte]): Boolean

  def read(path: String, iterByteSizeOpt: Option[Int]): Iterable[Array[Byte]]
}
