package tuktu.web.processors.analytics

import play.api.libs.iteratee.Enumeratee
import tuktu.api.DataPacket
import play.api.libs.json.JsObject
import tuktu.api.BaseProcessor
import scala.concurrent.Future
import tuktu.api.WebJsObject
import scala.concurrent.ExecutionContext.Implicits.global
import tuktu.api.WebJsFunctionObject
import tuktu.api.utils

/**
 * Adds a JS function to the code
 */
class FunctionProcessor(resultName: String) extends BaseProcessor(resultName) {
    var name: String = _
    var params: List[String] = _
    var body: String = _
    
    override def initialize(config: JsObject) {
        name = (config \ "name").as[String]
        params = (config \ "params").asOpt[List[String]].getOrElse(List[String]())
        body = (config \ "body").as[String]
    }
    
    override def processor(): Enumeratee[DataPacket, DataPacket] = Enumeratee.mapM((data: DataPacket) => Future {
        new DataPacket(for (datum <- data.data) yield {
            datum + (resultName -> new WebJsFunctionObject(
                    utils.evaluateTuktuString(name, datum),
                    params.map(p => utils.evaluateTuktuString(p, datum)),
                    utils.evaluateTuktuString(body, datum)
            ))
        })
    })
}

/**
 * Collects the outcome of a function
 */
class FunctionFetcherProcessor(resultName: String) extends BaseProcessor(resultName) {
    var body: String = _
    
    override def initialize(config: JsObject) {
        body = (config \ "body").as[String]
    }
    
    override def processor(): Enumeratee[DataPacket, DataPacket] = Enumeratee.mapM((data: DataPacket) => Future {
        new DataPacket(for (datum <- data.data) yield {
            datum + (resultName -> new WebJsObject(
                    "function() {" + utils.evaluateTuktuString(body, datum) + "}"
            ))
        })
    })
}