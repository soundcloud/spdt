package com.soundcloud.spdt

import play.api.libs.json._
import play.api.data.validation.ValidationError

object Feature {
  trait Type
  object Boolean extends Type
  object RealValued extends Type
}

//// sample
////
// @param features: analogous to a sparse vector for sample features
// @param label: optional class label of the sample
case class Sample(features: Map[Int,Double], label: Option[Int]) {
  import Sample.sampleWriter

  lazy val json = Json.stringify(Json.toJson(this))
}


object Sample {
  implicit val sampleReader: Reads[Sample] = new Reads[Sample] {
    def reads(json: JsValue): JsResult[Sample] = {
      val keys: Seq[Int]      = (json \ "features").as[Seq[Int]]
      val values: Seq[Double] = (json \ "values").as[Seq[Double]]
      val label: Option[Int]  = (json \ "label").asOpt[Int]

      (keys.length == values.length) match {
        case true  => JsSuccess(Sample(keys.zip(values).toMap, label))
        case false => JsError(Seq(JsPath() -> Seq(ValidationError("validate.error.expected.sparse.features"))))
      }
    }
  }

  implicit val sampleWriter: Writes[Sample] = new Writes[Sample] {
    def writes(sample: Sample): JsValue = {
      Json.obj(
        "features" -> sample.features.keys,
        "values"   -> sample.features.values,
        "label"    -> sample.label)
    }
  }

  def listFromJson(json: String): List[Sample] =
    (Json.parse(json) \ "samples").as[Seq[JsValue]].map(fromJson).toList

  def fromJson(s: String): Sample = fromJson(Json.parse(s))
  def fromJson(json: JsValue): Sample = json.as[Sample]

  def jsonList(samples: List[Sample]) =
    """{"samples":[%s]}""".format(samples.map(_.json).mkString(","))
}
