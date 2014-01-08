package com.soundcloud.spdt.serve

import org.scalatest.FlatSpecLike
import org.scalatra.test.scalatest._

import com.soundcloud.spdt.{
  SPDT,
  DecisionTree,
  Sample
}


class ApiServletTest extends ScalatraSuite with FlatSpecLike {

  var triggered: Boolean = false

  val spdt = new SPDT(List(0, 1), 0.5, numBins = 10)

  val servlet = new ApiServlet(
    "/some/dir",
    new HDFSAccess("http://somewhere.com"),
    spdt)

  addServlet(servlet, "/*")

  val contentType = Map("Content-Type" -> "application/json")

  behavior of "the classify endpoint"

  it should "on to POST /classify, classify a sample" in {
    val sampleJson = """{"features":[1,2],"values":[0.1,0.2],"label":1}"""

    post("/classify", sampleJson) {
      assert(status === 200)
      assert(body === """{"endpoint":"/classify","label":"unknown"}""")
    }
  }

  behavior of "the update endpoint"

  it should "on to POST /update, update the decision tree" in {
    val sampleJson =
      """{"samples":[{"features":[1,2],"values":[0.1,0.2],"label":1}, """ +
      """{"features":[1,2],"values":[0.1,0.2],"label":0}]}"""

    post("/update", sampleJson) {
      println(body)
      assert(status === 202)
      assert(body === """{"endpoint":"/update","num_samples":2,"updateId":0}""")
    }
  }
}

