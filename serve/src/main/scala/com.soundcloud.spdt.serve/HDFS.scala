package com.soundcloud.spdt.serve

import play.api.libs.json._
import play.api.libs.ws._
import java.net.URL


class HDFSAccess(baseUrl: String, user: Option[String] = None) extends FileSystem {

  import FileSystem._

  //val conf = Config(
  //  followRedirects = false,
  //  keepAlive = true,
  //  connectTimeout = 3000,
  //  readTimeout = 20000)

  //val http = new HttpClient(conf)

  val wsConfig = WSClientConfig()
  val ws = WS

  val userParam = user match {
    case Some(u) => s"&user.name=$u"
    case None    => ""
  }

  private def url(path: String, operation: String, paramStr: String = "") =
    new URL("%s%s?op=%s%s%s".format(baseUrl, path, operation, paramStr, userParam))


  def ls(path: String): List[Entry] = {
    val request = ws.
    val response = http.get(url(path, "LISTSTATUS"))
    val parsed   = (Json.parse(response.body.asString) \ "FileStatuses" \ "FileStatus")
    val prefix = if (path.last == '/') path else path + "/"

    parsed match {
      case ary: JsArray => {
        ary.value.map(item => {
          val modificationTime = (item \ "modificationTime").as[Long]
          val fullPath = prefix + (item \ "pathSuffix").as[String]

          if ((item \ "type").as[String] == "DIRECTORY") {
            Folder(fullPath, modificationTime)
          } else {
            val sizeInBytes = (item \ "length").as[Long]
            File(fullPath, modificationTime, sizeInBytes)
          }
        }).toList
      }
      case _ => List()
    }
  }

  def rm(path: String): Boolean =
    (Json.parse(http.delete(url(path, "DELETE", "&recursive=true")).body.asString)
      \ "boolean").as[Boolean]


  def read(path: String,
           iterByteSizeOpt: Option[Int] = None): Iterable[Array[Byte]] =
    new BodyIterable(path, iterByteSizeOpt)

  class BodyIterable(path: String,
                     iterByteSizeOpt: Option[Int]) extends Iterable[Array[Byte]] {
    def iterator = new Iterator[Array[Byte]] {
      var offset:Long = 0
      var continue = {
        val lsResults = ls(path)
        lsResults.length == 1 && lsResults.head.isInstanceOf[File]
      }

      def request(oset: Long): (Array[Byte],Boolean) = {
        val params = iterByteSizeOpt
          .map(size => "&offset=" + oset + "&length=" + size)
          .getOrElse("")

        val requestUrl = url(path, "OPEN", params)

        val response = httpFollow.get(requestUrl)
        val body = response.body.asBytes
        val code = response.status.code
        (body, code == 200 && iterByteSizeOpt.isDefined && body.length == iterByteSizeOpt.get)
      }

      def next: Array[Byte] = {
        val (bodyBytes, isIncomplete) = request(offset)
        continue = isIncomplete
        offset  += bodyBytes.length
        bodyBytes
      }

      def hasNext: Boolean = continue
    }
  }

  def create(path: String, body: String): Boolean =
    create(path, body.getBytes)

  def create(path: String, body: Array[Byte]): Boolean = {
    val redirectResponse = http.put(
      url = url(path, "CREATE"),
      body = RequestBody(MediaType.TEXT_PLAIN),
      requestHeaders = Headers())
    if (redirectResponse.status.code != 307) {
      return false
    } else {

      val location = new URL(
        redirectResponse.headers.get(new HeaderName("Location")).get.value)

      val uploadResponse = http.put(
        url = location,
        body = RequestBody(body, MediaType.TEXT_PLAIN),
        requestHeaders = Headers(HeaderName.ACCEPT -> "application/octet-stream"))

      uploadResponse.status.code == 201
    }
  }

  def append(path: String, body: String): Boolean =
    append(path, body.getBytes)

  def append(path: String, body: Array[Byte]): Boolean = {
    val redirectResponse = http.post(
      url = url(path, "APPEND"),
      body = Some(RequestBody(MediaType.TEXT_PLAIN)),
      requestHeaders = Headers())
    if (redirectResponse.status.code != 307) {
      return false
    } else {

      val location = new URL(
        redirectResponse.headers.get(new HeaderName("Location")).get.value)

      val uploadResponse = http.post(
        url = location,
        body = Some(RequestBody(body, MediaType.TEXT_PLAIN)),
        requestHeaders = Headers(HeaderName.ACCEPT -> "application/octet-stream"))

      uploadResponse.status.code == 200
    }
  }
}
