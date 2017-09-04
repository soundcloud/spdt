package com.soundcloud.spdt.serve

import play.api.libs.json._

import scalaj.http.{Http, HttpOptions}
import java.net.URL


class HDFSAccess(baseUrl: String, user: Option[String] = None) extends FileSystem {

  import FileSystem._


  private def httpRequest(path: String, operation: String, params: Map[String, String] = Map(), followRedirects: Boolean = false) =
    Http(baseUrl + path)
      .param("op", operation)
      .params(params ++ user.map(u => ("user.name", u)).toSeq)
      .timeout(3000, 20000)
      .option(HttpOptions.followRedirects(followRedirects))

  def ls(path: String): List[Entry] = {
    val response = httpRequest(path, "LISTSTATUS").asString
    val parsed = Json.parse(response.body) \ "FileStatuses" \ "FileStatus"
    val prefix = if (path.last == '/') path else path + "/"

    parsed match {
      case JsDefined(ary: JsArray) =>
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
      case _ => List()
    }
  }

  def rm(path: String): Boolean =
    (
      Json.parse(
        httpRequest(path, "DELETE", Map("recursive" -> "true")).method("DELETE").asString.body
      ) \ "boolean"
    ).as[Boolean]

  def read(path: String,
           iterByteSizeOpt: Option[Int] = None): Iterable[Array[Byte]] =
    new BodyIterable(path, iterByteSizeOpt)

  class BodyIterable(path: String,
                     iterByteSizeOpt: Option[Int]) extends Iterable[Array[Byte]] {
    def iterator = new Iterator[Array[Byte]] {
      var offset: Long = 0
      var continue = {
        val lsResults = ls(path)
        lsResults.length == 1 && lsResults.head.isInstanceOf[File]
      }

      def request(oset: Long): (Array[Byte], Boolean) = {
        val params = iterByteSizeOpt match {
          case Some(length) => Map("length" -> length.toString, "offset" -> oset.toString)
          case None => Map[String, String]()
        }

        val response = httpRequest(path, "OPEN", params, followRedirects = true).asBytes

        val body = response.body
        val code = response.code
        (body, code == 200 && iterByteSizeOpt.isDefined && body.length == iterByteSizeOpt.get)
      }

      def next: Array[Byte] = {
        val (bodyBytes, isIncomplete) = request(offset)
        continue = isIncomplete
        offset += bodyBytes.length
        bodyBytes
      }

      def hasNext: Boolean = continue
    }
  }

  def create(path: String, body: String): Boolean =
    create(path, body.getBytes)

  def create(path: String, body: Array[Byte]): Boolean = {
    val redirectResponse = httpRequest(path, "CREATE")
      .method("PUT")
      .asString

    if (redirectResponse.code != 307) {
      false
    } else {

      val location = redirectResponse.location.get

      val uploadResponse = Http(location)
        .put(body)
        .header("Content-Type", "application/octet-stream")
        .asBytes

      uploadResponse.code == 201
    }
  }

  def append(path: String, body: String): Boolean =
    append(path, body.getBytes)

  def append(path: String, body: Array[Byte]): Boolean = {
    val redirectResponse = httpRequest(path, "APPEND")
      .method("POST")
      .asString

    if (redirectResponse.code != 307) {
      false
    } else {
      val location = redirectResponse.location.get

      val uploadResponse = Http(location)
        .postData(body)
        .header("Content-Type", "text/plain")
        .header("Accept", "application/octet-stream")
        .asBytes

      uploadResponse.code == 200
    }
  }
}
