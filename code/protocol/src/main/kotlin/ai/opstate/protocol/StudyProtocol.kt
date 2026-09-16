package ai.opstate.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.UUID

const val STUDY_CHANNEL = "ai.opstate.study.v5"
const val MAX_CONTROL_BYTES = 64 * 1024

@Serializable
data class StudyMessage(
    val protocol_version: String = "rokid-study-v5",
    val message_id: String = UUID.randomUUID().toString(),
    val session_id: String,
    val session_epoch: Long,
    val sent_at_epoch_ms: Long,
    val type: String,
    val payload: JsonObject = JsonObject(emptyMap()),
    val idempotency_key: String = message_id,
    val expires_at_epoch_ms: Long? = null,
)

object StudyWire {
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    fun encode(message: StudyMessage): String = json.encodeToString(message).also {
        require(it.toByteArray().size <= MAX_CONTROL_BYTES) { "CONTROL_MESSAGE_TOO_LARGE" }
    }
    fun decode(value: String): StudyMessage {
        require(value.toByteArray().size <= MAX_CONTROL_BYTES) { "CONTROL_MESSAGE_TOO_LARGE" }
        return json.decodeFromString<StudyMessage>(value).also {
            require(it.protocol_version == "rokid-study-v5") { "PROTOCOL_VERSION_MISMATCH" }
            require(it.message_id.isNotBlank() && it.idempotency_key.isNotBlank()) { "MISSING_CORRELATION" }
            require(it.session_id.isNotBlank()) { "MISSING_SESSION" }
        }
    }
    fun payload(vararg entries: Pair<String, Any?>): JsonObject = buildJsonObject {
        entries.forEach { (key, value) -> put(key, when (value) {
            null -> JsonNull
            is JsonElement -> value
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }) }
    }
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}

/** Admission is atomic. Duplicate messages must replay their cached result, never repeat an effect. */
class SessionCommandGate(
    private val capacity: Int = 16_384,
    initialEpoch: Long = Long.MIN_VALUE,
    private val persistEpoch: (Long)->Unit = {},
) {
    enum class Admission { ACCEPT, DUPLICATE, EXPIRED, WRONG_SESSION, STALE_EPOCH, CAPACITY, PERSISTENCE_FAILED }
    private var session: String? = null
    private var epoch: Long = initialEpoch
    private val keys = hashSetOf<String>()

    @Synchronized
    fun admit(message: StudyMessage, nowInSenderEpochMs: Long): Admission {
        if (message.expires_at_epoch_ms?.let { nowInSenderEpochMs >= it } == true) return Admission.EXPIRED
        if (message.type == "SESSION_START") {
            if (message.session_epoch < epoch) return Admission.STALE_EPOCH
            if (message.session_epoch == epoch && message.session_id != session) return Admission.WRONG_SESSION
            if (message.session_epoch > epoch) {
                // Persist before accepting a command that can cause capture/display side effects.
                try { persistEpoch(message.session_epoch) }
                catch (_: Exception) { return Admission.PERSISTENCE_FAILED }
                epoch = message.session_epoch
                session = message.session_id
                keys.clear()
            }
        } else if (message.session_epoch != epoch || message.session_id != session) return Admission.WRONG_SESSION
        if (message.idempotency_key in keys) return Admission.DUPLICATE
        if (keys.size >= capacity) return Admission.CAPACITY
        keys += message.idempotency_key
        return Admission.ACCEPT
    }

    /** Retain the high-water epoch across stop/disconnect to reject delayed session starts. */
    @Synchronized fun stop() { session = null; keys.clear() }
}

@Serializable
data class ImageDescriptor(
    val capture_id: String,
    val session_id: String,
    val session_epoch: Long,
    val captured_at_epoch_ms: Long,
    val width: Int,
    val height: Int,
    val rotation_degrees: Int,
    val byte_length: Int,
    val sha256: String,
    val chunk_size: Int = 8 * 1024,
) {
    fun validate() {
        require(capture_id.isNotBlank() && session_id.isNotBlank()) { "INVALID_IMAGE_ID" }
        require(width in 1..8192 && height in 1..8192) { "INVALID_IMAGE_DIMENSIONS" }
        require(rotation_degrees in setOf(0, 90, 180, 270)) { "INVALID_IMAGE_ROTATION" }
        require(byte_length in 1..8 * 1024 * 1024 && chunk_size in 1..16 * 1024) { "INVALID_IMAGE_SIZE" }
        require(sha256.matches(Regex("[a-f0-9]{64}"))) { "INVALID_IMAGE_HASH" }
    }
    val chunkCount: Int get() = (byte_length + chunk_size - 1) / chunk_size
}

/** Used for actual Bluetooth chunks; P2P complete bodies undergo the same size/hash check. */
class ImageAssembly(val descriptor: ImageDescriptor, private val deadlineElapsedMs: Long) {
    init { descriptor.validate() }
    private val chunks = arrayOfNulls<ByteArray>(descriptor.chunkCount)
    @Synchronized
    fun add(index: Int, bytes: ByteArray, nowElapsedMs: Long): Boolean {
        require(nowElapsedMs < deadlineElapsedMs) { "IMAGE_TRANSFER_EXPIRED" }
        require(index in chunks.indices) { "INVALID_CHUNK_INDEX" }
        val expected = minOf(descriptor.chunk_size, descriptor.byte_length - index * descriptor.chunk_size)
        require(bytes.size == expected) { "CHUNK_SIZE_MISMATCH" }
        chunks[index]?.let { require(it.contentEquals(bytes)) { "CONFLICTING_DUPLICATE_CHUNK" } }
        chunks[index] = bytes.copyOf()
        return chunks.all { it != null }
    }
    @Synchronized
    fun finish(nowElapsedMs: Long): ByteArray {
        require(nowElapsedMs < deadlineElapsedMs) { "IMAGE_TRANSFER_EXPIRED" }
        require(chunks.all { it != null }) { "INCOMPLETE_IMAGE" }
        val result = ByteArray(descriptor.byte_length)
        chunks.forEachIndexed { index, bytes -> requireNotNull(bytes).copyInto(result, index * descriptor.chunk_size) }
        verifyImage(descriptor, result)
        return result
    }
    @Synchronized fun missingChunks(): List<Int> = chunks.indices.filter { chunks[it] == null }
}

fun verifyImage(descriptor: ImageDescriptor, bytes: ByteArray) {
    descriptor.validate()
    require(bytes.size == descriptor.byte_length) { "IMAGE_SIZE_MISMATCH" }
    require(StudyWire.sha256(bytes) == descriptor.sha256) { "IMAGE_HASH_MISMATCH" }
}

/** Offset is remote minus local. Deadlines stay local; only observation wall times are converted. */
data class PeerClockEstimate(val remoteMinusLocalMs: Long, val uncertaintyMs: Long) {
    fun remoteToLocal(remoteEpochMs: Long) = remoteEpochMs - remoteMinusLocalMs
    fun localToRemote(localEpochMs: Long) = localEpochMs + remoteMinusLocalMs
    companion object {
        fun fromPing(localSentMs: Long, remoteReceivedMs: Long, localReceivedMs: Long): PeerClockEstimate {
            require(localReceivedMs >= localSentMs)
            val roundTrip = localReceivedMs - localSentMs
            return PeerClockEstimate(remoteReceivedMs - (localSentMs + roundTrip / 2), roundTrip / 2)
        }
    }
}
