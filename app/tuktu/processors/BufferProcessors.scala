package tuktu.processors

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import play.api.libs.iteratee.Enumeratee
import play.api.libs.json.JsValue
import play.api.libs.concurrent.Akka
import play.api.Play.current
import tuktu.api._
import play.api.libs.json.JsObject
import play.api.cache.Cache
import scala.collection.mutable.ListBuffer

/**
 * This actor is used to buffer stuff in
 */
class BufferActor(remoteGenerator: ActorRef) extends Actor with ActorLogging {
    implicit val timeout = Timeout(Cache.getAs[Int]("timeout").getOrElse(5) seconds)
    
    val buffer = collection.mutable.ListBuffer[Map[String, Any]]()
    
    // Send init packet to the remote generator
    remoteGenerator ! new InitPacket
    
    def receive() = {
        case "release" => {
            // Create datapacket and clear buffer
            val dp = new DataPacket(buffer.toList.asInstanceOf[List[Map[String, Any]]])
            buffer.clear
            
            // Push forward to remote generator
            remoteGenerator ! dp
            
            sender ! "ok"
        }
        case sp: StopPacket => {
            remoteGenerator ! sp
            self ! PoisonPill
        }
        case item: Map[String, Any] => {
            buffer += item
            sender ! "ok"
        }
    }
}

/**
 * This actor is used to buffer grouped stuff in
 */
class GroupedBufferActor(remoteGenerator: ActorRef, fields: List[String]) extends Actor with ActorLogging {
    implicit val timeout = Timeout(Cache.getAs[Int]("timeout").getOrElse(5) seconds)
    
    val buffer = collection.mutable.Map[List[Any], ListBuffer[Map[String, Any]]]()
    
    remoteGenerator ! new InitPacket
            
    def receive() = {
        case "release" => {
            // Create datapackets and clear buffer
            buffer.foreach(item => remoteGenerator ! new DataPacket(item._2.toList))
            buffer.clear
            
            sender ! "ok"
        }
        case sp: StopPacket => {
            remoteGenerator ! sp
            self ! PoisonPill
        }
        case item: Map[String, Any] => {            
            val key = fields.map(field => item(field))
            
            // make sure a ListBuffer exists for this key
            if (!buffer.contains(key)) buffer += (key -> ListBuffer[Map[String, Any]]() )
            
            buffer(key) += item            
            sender ! "ok"
        }
    }
    
}

/**
 * Buffers datapackets until we have a specific amount of them
 */
class SizeBufferProcessor(genActor: ActorRef, resultName: String) extends BufferProcessor(genActor, resultName) {
    implicit val timeout = Timeout(Cache.getAs[Int]("timeout").getOrElse(5) seconds)
    
    var maxSize = -1
    var curCount = 0
    
    // Set up the buffering actor
    val bufferActor = Akka.system.actorOf(Props(classOf[BufferActor], genActor))
    
    override def initialize(config: JsObject) {
        maxSize = (config \ "size").as[Int]
    }
    
    override def processor(): Enumeratee[DataPacket, DataPacket] = Enumeratee.mapM((data: DataPacket) => {
        // Iterate over our data and add to the buffer
        val fut = Future.sequence(for (datum <- data.data) yield bufferActor ? datum)
        
        // Wait for all of them to finish
        Await.result(fut, 30 seconds)
        
        // Increase counter
        curCount += 1
        
        // See if we need to release
        if (curCount == maxSize) {
            // Send the relase but forget the result
            curCount = 0
            val dummyFut = bufferActor ? "release"
            dummyFut.onComplete {
                case _ => {}
            }
        }
        
        Future {data}
    }) compose Enumeratee.onEOF(() => {
        Await.result(bufferActor ? "release", Cache.getAs[Int]("timeout").getOrElse(5) seconds)
        bufferActor ! new StopPacket
    })
}

/**
 * Buffers datapackets for a given amount of time and then releases the buffer for processing
 */
class TimeBufferProcessor(genActor: ActorRef, resultName: String) extends BufferProcessor(genActor, resultName) {
    implicit val timeout = Timeout(Cache.getAs[Int]("timeout").getOrElse(5) seconds)
    
    var interval = -1
    var cancellable: Cancellable = null
    
    // Set up the buffering actor
    val bufferActor = Akka.system.actorOf(Props(classOf[BufferActor], genActor))
    
    override def initialize(config: JsObject) {
        interval = (config \ "interval").as[Int]
        
        // Schedule periodic release
        cancellable =
            Akka.system.scheduler.schedule(interval milliseconds,
            interval milliseconds,
            bufferActor,
            "release"
        )
    }
    
    override def processor(): Enumeratee[DataPacket, DataPacket] = Enumeratee.mapM((data: DataPacket) => {
        // Iterate over our data and add to the buffer
        data.data.foreach(datum => bufferActor ! datum)
        
        Future {data}
    }) compose Enumeratee.onEOF(() => {
        cancellable.cancel
        Await.result(bufferActor ? "release", Cache.getAs[Int]("timeout").getOrElse(5) seconds)
        bufferActor ! new StopPacket
    })
}

/**
 * Buffers until EOF (end of data stream) is found
 */
class EOFBufferProcessor(genActor: ActorRef, resultName: String) extends BufferProcessor(genActor, resultName) {
    implicit val timeout = Timeout(Cache.getAs[Int]("timeout").getOrElse(5) seconds)
    
    // Set up the buffering actor
    val bufferActor = Akka.system.actorOf(Props(classOf[BufferActor], genActor))
    
    override def processor(): Enumeratee[DataPacket, DataPacket] = Enumeratee.mapM((data: DataPacket) => {
        // Iterate over our data and add to the buffer
        val fut = Future.sequence(for (datum <- data.data) yield bufferActor ? datum)
        
        // Wait for all of them to finish
        fut.map {
            case _ => data
        }
    }) compose Enumeratee.onEOF(() => {
        Await.result(bufferActor ? "release", Cache.getAs[Int]("timeout").getOrElse(5) seconds)
        bufferActor ! new StopPacket
    })
}

/**
 * Buffers and Groups data
 */
class GroupByBuffer(genActor: ActorRef, resultName: String) extends BufferProcessor(genActor, resultName) {
    implicit val timeout = Timeout(Cache.getAs[Int]("timeout").getOrElse(5) seconds)
    
    // Set up the buffering actor
    var bufferActor: ActorRef = _
    
    var fields: List[String] = _
    
    override def initialize(config: JsObject) {
        // Get the field to group on
        fields = (config \ "fields").as[List[String]]
        bufferActor = Akka.system.actorOf(Props(classOf[GroupedBufferActor], genActor, fields))
    }
    
    override def processor(): Enumeratee[DataPacket, DataPacket] = Enumeratee.map((data: DataPacket) => {
        // Iterate over our data and add to the buffer
        val fut = Future.sequence(for (datum <- data.data) yield bufferActor ? datum)
        
        // Wait for all of them to finish
        Await.result(fut, Cache.getAs[Int]("timeout").getOrElse(5) seconds)
        
        data
    }) compose Enumeratee.onEOF(() => {
        Await.result(bufferActor ? "release", Cache.getAs[Int]("timeout").getOrElse(5) seconds)
        bufferActor ! new StopPacket
    })
}

/**
 * Hybrid processor that either buffers data until a signal is received, or sends the signal.
 * This means that you MUST always have 2 instances of this processor active, in separate
 * branches.
 */
class SignalBufferProcessor(genActor: ActorRef, resultName: String) extends BufferProcessor(genActor, resultName) {
    implicit val timeout = Timeout(Cache.getAs[Int]("timeout").getOrElse(5) seconds)
    
    // Set up the buffering actor
    var bufferActor: ActorRef = _
    var signaller = false
    
    override def initialize(config: JsObject) {
        // Get name of bufferer
        val bufferName = (config \ "signal_name").as[String]
        // Remote or not?
        val node = (config \ "node").asOpt[String]
        // Signaller or signalee?
        signaller = (config \ "is_signaller").as[Boolean]
        
        if (signaller) {
            // This is the signaller, we need to fetch the remote actor
            node match {
                case Some(n) => {
                    // Get it from a remote location
                    val clusterNodes = Cache.getOrElse[scala.collection.mutable.Map[String, ClusterNode]]("clusterNodes")(scala.collection.mutable.Map())
                    val fut = Akka.system.actorSelection("akka.tcp://application@" + n  + ":" + clusterNodes(n).akkaPort + "/user/SignalBufferActor_" + bufferName) ? Identify(None)
                    bufferActor = Await.result(fut.mapTo[ActorIdentity], timeout.duration).getRef
                }
                case None => {
                    // It has be alive locally
                    val fut = Akka.system.actorSelection("user/SignalBufferActor_" + bufferName) ? Identify(None)
                    bufferActor = Await.result(fut.mapTo[ActorIdentity], timeout.duration).getRef
                }
            }
        }
        else {
            // This is not the signaller, we need to keep track of the data
            bufferActor = Akka.system.actorOf(Props(classOf[SignalBufferActor], genActor), name = "SignalBufferActor_" + bufferName)
        }
    }
    
    override def processor(): Enumeratee[DataPacket, DataPacket] = Enumeratee.mapM((data: DataPacket) => {
        // Send this packet as-is to our bufferer if we are not the signaller
        if (!signaller) {
            val future = bufferActor ? data
            future.map { 
                case _ => data
            }
        }
        else Future { data }
    }) compose Enumeratee.onEOF(() => {
        // See if we are the signaller or not
        if (signaller) {
            Await.result(bufferActor ? "release", Cache.getAs[Int]("timeout").getOrElse(5) seconds)
            bufferActor ! new StopPacket
        }
    })
}

/**
 * Buffer actor to go with the signal buffer processor
 */
class SignalBufferActor(remoteGenerator: ActorRef) extends Actor with ActorLogging {
    implicit val timeout = Timeout(Cache.getAs[Int]("timeout").getOrElse(5) seconds)
    
    val buffer = collection.mutable.ListBuffer[DataPacket]()
    
    remoteGenerator ! new InitPacket
    
    def receive() = {
        case "release" => {
            // Send data packets to remote generator one by one
            buffer.foreach(dp => remoteGenerator ! dp)
            
            // Clear buffer
            buffer.clear
            sender ! "ok"
        }
        case sp: StopPacket => {
            remoteGenerator ! sp
            self ! PoisonPill
        }
        case dp: DataPacket => {
            buffer += dp
            sender ! "ok"
        }
    }
}

/**
 * Splits the elements of a single data packet into separate data packets (one per element)
 */
class DataPacketSplitterProcessor(genActor: ActorRef, resultName: String) extends BufferProcessor(genActor, resultName) {
    implicit val timeout = Timeout(Cache.getAs[Int]("timeout").getOrElse(5) seconds)
    
    // Set up the splitting actor
    val splitActor = Akka.system.actorOf(Props(classOf[SplitterActor], genActor))
    
    override def processor(): Enumeratee[DataPacket, DataPacket] = Enumeratee.mapM((data: DataPacket) => {
        val futures = Future.sequence(for (datum <- data.data) yield splitActor ? datum)
        
        futures.map {
            case _ => data
        }
    }) compose Enumeratee.onEOF(() => splitActor ! new StopPacket)
}

/**
 * Actor for forwarding the split data packets
 */
class SplitterActor(remoteGenerator: ActorRef) extends Actor with ActorLogging {
    remoteGenerator ! new InitPacket
    
    def receive() = {
        case sp: StopPacket => {
            remoteGenerator ! sp
            self ! PoisonPill
        }
        case item: Map[String, Any] => {
            // Directly forward
            remoteGenerator ! new DataPacket(List(item))
            sender ! "ok"
        }
    }
}