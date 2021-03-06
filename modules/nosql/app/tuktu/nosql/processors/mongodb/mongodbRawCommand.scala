package tuktu.nosql.processors.mongodb

import play.api.libs.iteratee.Enumeratee
import play.api.libs.json.JsObject
import play.modules.reactivemongo.json._
import reactivemongo.api._
import reactivemongo.api.commands.Command
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import tuktu.api._

/**
 * Provides a helper to run specified database commands (as long as the command result is less than 16MB in size).
 */

class MongoDBRawCommandProcessor(resultName: String) extends BaseProcessor(resultName) {
    var command: JsObject = _
    var db: DefaultDB = _
    var resultOnly: Boolean = _

    override def initialize(config: JsObject) {
        // Prepare db connection
        val dbHosts = (config \ "hosts").as[List[String]]
        val dbName = (config \ "database").as[String]
        val driver = new MongoDriver
        val connection = driver.connection(dbHosts)
        db = connection(dbName)

        // Get command
        command = (config \ "command").as[JsObject]

        // Get result format
        resultOnly = (config \ "resultOnly").asOpt[Boolean].getOrElse(false)

    }

    override def processor(): Enumeratee[DataPacket, DataPacket] = Enumeratee.mapM(data => {
        val runner = Command.run(JSONSerializationPack)
        val futureResult = runner.apply(db, runner.rawCommand(command)).one[JsObject]
        futureResult.map { result =>
            if (resultOnly) {
                new DataPacket(for (datum <- data.data) yield { datum + (resultName -> (result \ "result")) })
            } else {
                new DataPacket(for (datum <- data.data) yield { datum + (resultName -> result) })
            }
        }
    })

}