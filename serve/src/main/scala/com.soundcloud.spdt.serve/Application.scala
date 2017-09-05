package com.soundcloud.spdt.serve

import java.util.{Locale, TimeZone}

import com.soundcloud.spdt.SPDT
import io.prometheus.client.exporter.MetricsServlet
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.ServletHolder
import org.eclipse.jetty.webapp.WebAppContext
import org.scalatra.ScalatraServlet
import org.slf4j.LoggerFactory



object Application {

  def envGet(name: String): String = sys.env.get(name) match {
    case Some(value) =>
      log.info(s"Parameter [$name] initialized to [$value].")
      value
    case None =>
      throw new IllegalArgumentException(s"Parameter [$name] is not specified!")
  }

  val servlets = scala.collection.mutable.Queue[ScalatraServlet]()
  val log = LoggerFactory.getLogger(this.getClass.getName)

  val port = envGet("WEB_PORT").toInt
  val webHdfsBaseUrl = envGet("WEB_HDFS_BASE_URL")
  val modelFolder = envGet("SPDT_DIRECTORY")

  def main(args: Array[String]) {
    localize()

    log.info("Checking HDFS for model in directory: %s".format(modelFolder))

    val hdfs = new HDFSAccess(webHdfsBaseUrl)
    val modelPath = hdfs.ls(modelFolder)
      .filter(_.path.contains(".spdt"))
      .maxBy(_.modifiedAt)
      .path

    log.info("Loading model from hdfs: %s".format(modelPath))

    val spdt = SPDT.fromPickle(new String(hdfs.read(modelPath).iterator.reduce(_ ++ _)))


    log.info("Starting server on port %d.".format(port))

    servlets += new ApiServlet(
      modelPath = modelPath,
      hdfs = hdfs,
      spdtSeed = spdt,
      saveSnapshots = true)

    val server = new Server(port)
    val context = new WebAppContext("serve/src/main/webapp", "/")
    context.addServlet(new ServletHolder(new MetricsServlet()), "/metrics")
    server.setHandler(context)

    try {
      server.start()
      server.join()
      log.info("http server for 'spdt.serve' up")
    } catch {
      case e: Exception =>
        e.printStackTrace()
        System.exit(1)
    }
  }

  private def localize() {
    Locale.setDefault(Locale.ENGLISH)
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
  }
}

