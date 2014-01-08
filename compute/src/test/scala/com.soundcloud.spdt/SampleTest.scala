package com.soundcloud.spdt

import org.scalatest.FlatSpec


case class SampleTest() extends FlatSpec {

  behavior of "the sample class json method"

  it should "serialize to json" in {
    val sample = Sample(Map(1->0.1, 2->0.2), Some(1))
    assert(sample.json === """{"features":[1,2],"values":[0.1,0.2],"label":1}""")
  }

  it should "serialize to without a label" in {
    val sample = Sample(Map(1->0.1, 2->0.2), None)
    assert(sample.json === """{"features":[1,2],"values":[0.1,0.2],"label":null}""")
  }

  behavior of "the sample companion object"

  it should "extract a single sample" in {
    val sampleJson = """{"features":[1,2],"values":[0.1,0.2],"label":1}"""
    val sampleExtracted = Sample(Map(1->0.1, 2->0.2), Some(1))
    assert(Sample.fromJson(sampleJson) === sampleExtracted)
  }

  it should "extract a single sample without a label" in {
    val sampleJsonNull = """{"features":[1,2],"values":[0.1,0.2],"label":null}"""
    val sampleJsonNone = """{"features":[1,2],"values":[0.1,0.2]}"""
    val sampleExtracted = Sample(Map(1->0.1, 2->0.2), None)
    assert(Sample.fromJson(sampleJsonNull) === sampleExtracted)
    assert(Sample.fromJson(sampleJsonNone) === sampleExtracted)
  }

  it should "extract a list of samples" in {
    val sampleJson =
      """{"samples":[""" +
      """{"features":[1,2],"values":[0.1,0.2],"label":1},""" +
      """{"features":[3,4],"values":[0.3,0.4],"label":0}""" +
      """]}"""
    val sampleExtracted = List(
      Sample(Map(1->0.1, 2->0.2), Some(1)),
      Sample(Map(3->0.3, 4->0.4), Some(0)))
    assert(Sample.listFromJson(sampleJson) === sampleExtracted)
  }

  it should "serialize a list of samples" in {
    val samples = List(
      Sample(Map(1->0.1, 2->0.2), Some(1)),
      Sample(Map(3->0.3, 4->0.4), Some(0)))
    assert(Sample.jsonList(samples) ===
      """{"samples":[""" +
      """{"features":[1,2],"values":[0.1,0.2],"label":1},""" +
      """{"features":[3,4],"values":[0.3,0.4],"label":0}""" +
      """]}""")
  }
}
