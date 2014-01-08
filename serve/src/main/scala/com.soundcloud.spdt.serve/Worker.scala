package com.soundcloud.spdt.serve

import io.prometheus.client.exporter.MetricsServlet

import System.{ getProperty => property }
import java.util.{ Date, Locale, TimeZone }
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.webapp.WebAppContext
import org.eclipse.jetty.servlet.{ ServletContextHandler, ServletHolder }

import com.soundcloud.spdt.{ SPDT, DecisionTree }

import org.scalatra.ScalatraServlet

import java.util.concurrent.atomic.AtomicReference

import org.slf4j.LoggerFactory


object Worker {

  val servlets = scala.collection.mutable.Queue[ScalatraServlet]()
  val log = LoggerFactory.getLogger(this.getClass.getName)

  def main(args: Array[String]) {
    localize()

    log.info("Checking HDFS for model in directory: %s".format(property("spdt.directory")))

    val hdfs = new HDFSAccess(property("web.hdfs"))
    val modelFolder = property("spdt.directory")
    val modelPath = hdfs.ls(modelFolder)
      .filter(_.path.contains(".spdt"))
      .sortBy(_.modifiedAt)
      .last
      .path

    log.info("Loading model from hdfs: %s".format(modelPath))

    val spdt = SPDT.fromPickle(new String(hdfs.read(modelPath).iterator.reduce(_ ++ _)))

    log.info("Starting server on port %s.".format(property("web.port")))

    servlets += new ApiServlet(
      modelPath = modelPath,
      hdfs = hdfs,
      spdtSeed = spdt,
      saveSnapshots = true)

    val server = new Server(property("web.port").toInt)
    val context = new WebAppContext("serve/src/main/webapp", "/")
    context.addServlet(new ServletHolder(new MetricsServlet()), "/metrics")
    server.setHandler(context)

    try {
      server.start()
      server.join()
      log.info("http server for 'spdt.serve' up")
    } catch {
      case e: Exception => {
        e.printStackTrace()
        System.exit(1)
      }
    }
  }

  private def localize() {
    Locale.setDefault(Locale.ENGLISH)
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
  }
}

