package ai.opstate.rokid.glasses.capture

import ai.opstate.protocol.ImageDescriptor
import ai.opstate.protocol.StudyWire
import ai.opstate.protocol.verifyImage
import android.util.AtomicFile
import kotlinx.serialization.json.*
import java.io.File

/** Durable queue. Transfer ACKs never delete originals; only the phone's journal commit does. */
class PhotoBuffer(private val directory: File) {
    data class Entry(val descriptor: ImageDescriptor,val metadata: JsonObject,val file: File)
    init {check(directory.isDirectory || directory.mkdirs()) {"BUFFER_DIRECTORY_UNAVAILABLE"}}
    private val index=readEntries().associateBy {it.descriptor.capture_id}.toMutableMap()
    // entries() is polled about once a second by the buffer-status path, so the sort is cached and
    // dropped on mutation instead of re-sorting the whole queue on every call.
    private var sorted: List<Entry>?=null
    fun entries(): List<Entry> = sorted ?: index.values.sortedWith(compareBy({it.descriptor.captured_at_epoch_ms},{it.descriptor.capture_id})).also {sorted=it}
    private fun readEntries(): List<Entry> = directory.listFiles().orEmpty().filter {it.extension=="json"}.map {manifest->
        val row=StudyWire.json.parseToJsonElement(AtomicFile(manifest).readFully().toString(Charsets.UTF_8)).jsonObject
        val descriptor=StudyWire.json.decodeFromJsonElement<ImageDescriptor>(row.getValue("image"))
        descriptor.validate()
        val jpeg=file(descriptor.capture_id,"jpg")
        check(jpeg.isFile) {"BUFFER_IMAGE_MISSING"}
        Entry(descriptor,row.getValue("metadata").jsonObject,jpeg)
    }.sortedWith(compareBy({it.descriptor.captured_at_epoch_ms},{it.descriptor.capture_id}))
    fun put(descriptor: ImageDescriptor,jpeg: ByteArray,metadata: JsonObject): Entry {
        verifyImage(descriptor,jpeg)
        check(directory.usableSpace>=128L*1024*1024+jpeg.size) {"GLASSES_STORAGE_LOW"}
        val target=file(descriptor.capture_id,"jpg")
        if(target.exists()) check(StudyWire.sha256(target.readBytes())==descriptor.sha256) {"BUFFER_ID_COLLISION"}
        else write(target,jpeg)
        write(file(descriptor.capture_id,"json"),StudyWire.payload("image" to StudyWire.json.encodeToJsonElement(descriptor),"metadata" to metadata).toString().toByteArray())
        return Entry(descriptor,metadata,target).also {index[descriptor.capture_id]=it;sorted=null}
    }
    fun commit(id: String,sha: String,length: Int): Boolean {
        val entry=entries().firstOrNull {it.descriptor.capture_id==id} ?: return false
        require(entry.descriptor.sha256==sha && entry.descriptor.byte_length==length) {"BUFFER_COMMIT_MISMATCH"}
        // Delete manifest first: a crash may leave an unreferenced JPEG, never a dangling committed entry.
        check(file(id,"json").delete()) {"BUFFER_COMMIT_FAILED"}
        index.remove(id);sorted=null
        check(entry.file.delete()) {"BUFFER_IMAGE_DELETE_FAILED"}
        return true
    }
    val freeBytes get()=directory.usableSpace
    private fun file(id: String,extension: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) {"INVALID_BUFFER_ID"}
        return File(directory,"$id.$extension")
    }
    private fun write(target: File,bytes: ByteArray) {
        val atomic=AtomicFile(target);val output=atomic.startWrite()
        try {output.write(bytes);atomic.finishWrite(output)} catch(error: Exception) {atomic.failWrite(output);throw error}
    }
}
