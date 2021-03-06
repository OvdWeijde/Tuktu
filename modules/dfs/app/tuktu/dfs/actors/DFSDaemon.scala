package tuktu.dfs.actors

import akka.actor.ActorLogging
import akka.actor.Actor
import java.io.File
import play.api.cache.Cache
import play.api.Play
import play.api.Play.current
import java.io.IOException
import tuktu.api._
import scala.util.hashing.MurmurHash3
import java.io.BufferedWriter
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import tuktu.dfs.util.util.getIndex
import tuktu.api.DFSListRequest
import tuktu.api.DFSResponse
import tuktu.api.DFSElement


/**
 * Central point of communication for the DFS
 */
class DFSDaemon extends Actor with ActorLogging {
    // File map to keep in-memory. A map from a list of (sub)directories to a hashset containing the files
    val dfsTable = collection.mutable.Map[List[String], collection.mutable.Map[String, DFSElement]]()
    // Get the prefix
    val prefix = Play.current.configuration.getString("tuktu.dfs.prefix").getOrElse("dfs")
    
    // Open files
    val openWriteFiles = collection.mutable.Map[String, BufferedWriter]()
    val openReadFiles = collection.mutable.HashSet[String]()
    // Local files, just for reference
    val localOpenFiles = collection.mutable.HashSet[String]()
    
    /**
     * Resolves a file, gives a DFSReply
     */
    private def resolveFile(filename: String): Option[File] = {
        // Get indexes
        val (index, dirIndex) = getIndex(filename)
        
        // See if it exists
        if (!dfsTable.contains(index) && !dfsTable.contains(dirIndex)) None
        else Some(new File(prefix + "/" + filename))
    }
    
    /**
     * Creates a directory on disk and adds it to the DFS
     */
    private def makeDir(index: List[String], filename: String) = {
        def makeDirsHelper(partialIndex: List[String]): Unit = {
            if (partialIndex.size < index.size) {
                // Check if this partial index exists or not
                if (!dfsTable.contains(partialIndex))
                    dfsTable += partialIndex -> collection.mutable.Map[String, DFSElement]()
                if (!dfsTable(partialIndex).contains(index(partialIndex.size)))
                    dfsTable(partialIndex) += index(partialIndex.size) -> new DFSElement(true)
                
                // Recurse if necessary
                if (partialIndex.size < index.size - 1)
                    makeDirsHelper(partialIndex ++ List(index(partialIndex.size)))
            }
        }
        
        val dirName = prefix + "/" + filename
        if (!dfsTable.contains(index)) {
            val file = new File(dirName)
            val res = if (!file.exists) file.mkdirs else true
                
            // Add to our DFS Table
            if (res) {
                makeDirsHelper(List())
                dfsTable += index -> collection.mutable.Map[String, DFSElement]()
            }
            
            // Return success or not
            res
        }
        else true // Already existed
    }
    
    /**
     * Creates a file or directory and adds it to the DFS
     */
    private def createFile(filename: String, isDirectory: Boolean): Option[File] = {
        // Get indexes
        val (index, dirIndex) = getIndex(filename)
        
        // See if we need to make a directory or a file
        if (isDirectory) {
            val success = makeDir(index, filename)
            if (success) Some(new File(prefix + "/" + filename))
            else None
        }
        else {
            // It's a file, first see if we need to/can make a dir
            val dirSuccess = makeDir(dirIndex, dirIndex.mkString("/"))
            if (!dirSuccess) None
            else {
                // Make the file
                val res = try {
                    // Make file
                    val file = new File(prefix + "/" + filename)
                    file.createNewFile
                    
                    // Add to map
                    dfsTable(dirIndex) += index.takeRight(1).head -> new DFSElement(false)
                    
                    Some(file)
                } catch {
                    case e: IOException => {
                        log.warning("Failed to create DFS file " + filename)
                        None
                    }
                }
                
                res
            }
        }
    }
    
    /**
     * Recursively deletes directories and files in it
     */
    private def deleteDirectory(directory: File): Boolean = {
        // Get all files in this directory
        val files = directory.listFiles
        (for (file <- files) yield {
            // Recurse or not?
            if (file.isDirectory) deleteDirectory(file)
            else file.delete
        }).toList.foldLeft(true)(_ && _)
    }
    
    /**
     * Deletes a file or directory
     */
    private def deleteFile(filename: String, isDirectory: Boolean) = {
        // Get indexes
        val (index, dirIndex) = getIndex(filename)
        
        // See if it's a directory or a file
        if (isDirectory) {
            // Delete directory from our DFS Table and from disk
            val res = deleteDirectory(new File(prefix + "/" + filename))
            dfsTable -= index
            
            res
        }
        else {
            // Remove the file from disk and DFS
            val res = new File(prefix + "/" + filename).delete
            if (dfsTable(dirIndex).size == 1) dfsTable -= dirIndex
            else dfsTable(dirIndex) -= index.takeRight(1).head
            
            res
        }
    }
    
    /**
     * On initialization, add all the current files to the in memory table store
     */
    private def initialize(directory: File): Unit = {     
        val (index, dirIndex) = getIndex(directory.toString)
        // initialize this directory
        dfsTable += index -> collection.mutable.Map[String, DFSElement]()
        // add files or add another directory
        directory.listFiles.foreach { file => {
                if(file.isDirectory) {
                    dfsTable(index) += file.toString -> new DFSElement(true)
                    initialize(file)
                }
                else
                    dfsTable(index) += file.toString -> new DFSElement(false)
            }
        }        
    }
    
    def receive() = {
        case rr: DFSReadRequest => {
            // Fetch the file and send back
            sender ! new DFSReadReply(resolveFile(rr.filename))
        }
        case cr: DFSCreateRequest => {
            // Create file and send back
            sender ! new DFSCreateReply(createFile(cr.filename, cr.isDirectory))
        }
        case dr: DFSDeleteRequest => {
            // Delete the file and send back
            sender ! new DFSDeleteReply(deleteFile(dr.filename, dr.isDirectory))
        }
        case or: DFSOpenRequest => {
            val filename = prefix + "/" + or.filename
            // Create first if needed
            if (!openWriteFiles.contains(filename)) createFile(or.filename, false)
            
            if (!openWriteFiles.contains(filename)) {
                // Open file
                val file = new File(filename)
                // Add to our list
                openWriteFiles += filename -> new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), or.encoding))
            }
        }
        case lor: DFSLocalOpenRequest => {
            val filename = prefix + "/" + lor.filename
            if (!localOpenFiles.contains(filename)) localOpenFiles += filename
        }
        case cr: DFSCloseRequest => {
            val filename = prefix + "/" + cr.filename
            if (openWriteFiles.contains(filename)) {
                // Close writer
                openWriteFiles(filename).flush
                openWriteFiles(filename).close
                // Remove from map
                openWriteFiles -= filename
            }
        }
        case lcr: DFSLocalCloseRequest => {
            localOpenFiles -= lcr.filename
        }
        case wr: DFSWriteRequest => {
            val filename = prefix + "/" + wr.filename
            // Write to our file
            if (openWriteFiles.contains(filename))
                openWriteFiles(filename).write(wr.content)
        }
        case init: InitPacket => {
            // Check if directory exists, if not, make it
            val rootFolder = new File(prefix)
            if (!rootFolder.exists)
                rootFolder.mkdirs
            
            initialize(rootFolder)
        }
        case lr: DFSListRequest => {
            // Send back the files in this folder, if any
            val filename = prefix + "/" + lr.filename
            val (index, dirIndex) = getIndex(filename)
            
            // Check if this is a directory or a file
            val response = {
                if (dfsTable.contains(index))
                    // It's a directory
                    Some(new DFSResponse(dfsTable(index).map(elem => elem._1.drop(prefix.size + 1) -> elem._2).toMap, true))
                else {
                    if (dfsTable.contains(dirIndex))
                        // It's a file
                        Some(new DFSResponse(Map(lr.filename.drop(prefix.size + 1) -> new DFSElement(false)), false))
                    else
                        // Not found
                        None
                }
            }
            
            // Send back response
            sender ! response
        }
        case oflr: DFSOpenFileListRequest => {
            // Combine remotely opened ones and local ones
            sender ! new DFSOpenFileListResponse(
                    localOpenFiles toList,
                    openWriteFiles.keys toList,
                    openReadFiles toList
            )
        }
        case orr: DFSOpenReadRequest => openReadFiles += orr.filename
        case crr: DFSCloseReadRequest => openReadFiles -= crr.filename
        case _ => {}
    }
}