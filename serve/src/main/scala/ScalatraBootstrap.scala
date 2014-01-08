import org.scalatra._

import com.soundcloud.spdt.serve.ApiServlet
import com.soundcloud.spdt.serve.Worker

import org.slf4j.LoggerFactory

import javax.servlet.ServletContext


class Scalatra extends LifeCycle {

  val log = LoggerFactory.getLogger(this.getClass.getName)

  override def init(context: ServletContext) {

    log.info(msg("Starting worker servlets."))

    Worker.servlets.map(servlet => context.mount(servlet, "/*"))

    log.info(msg("Worker servlets mounted."))
  }

  override def destroy(context: ServletContext) {
    log.info(msg("Shutting down."))
  }

  private def msg(str: String) = "[Scalatra] " + str

}

