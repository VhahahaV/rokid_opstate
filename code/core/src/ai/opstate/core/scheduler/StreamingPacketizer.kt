package ai.opstate.core.scheduler

/** The cheap, prefix-causal front end used by the HR packet stream. */
data class VisualSignature(val phash: Long, val histogram: List<Double>) {
    init { require(histogram.size == 48) }
    fun distance(other: VisualSignature): Double {
        val hamming = java.lang.Long.bitCount(phash xor other.phash) / 64.0
        val variation = histogram.zip(other.histogram).sumOf { (a, b) -> kotlin.math.abs(a - b) } / 6.0
        return (hamming + variation) / 2.0
    }
}

data class ObservationPacket(
    val packetId: String,
    val cutoffEpochMs: Long,
    val triggerTypes: Set<String>,
    val newEventIds: List<String>,
    val imageIds: List<String>,
    val reusedImageIds: List<String>,
    val latestImageAgeMs: Long?,
    val observationGap: Boolean,
)

class StreamingPacketizer(
    private val visualThreshold: Double = 0.44,
    private val visualCooldownMs: Long = 180_000,
    private val digitalDebounceMs: Long = 15_000,
    private val digitalMaxWaitMs: Long = 30_000,
    private val heartbeatMs: Long = 900_000,
    private val gapTimeoutMs: Long = 120_000,
) {
    fun configuration(): Map<String,Any> = linkedMapOf(
        "version" to "phash_histogram_v2","visual_threshold" to visualThreshold,"visual_cooldown_ms" to visualCooldownMs,
        "digital_debounce_ms" to digitalDebounceMs,"digital_max_wait_ms" to digitalMaxWaitMs,"heartbeat_ms" to heartbeatMs,
        "gap_timeout_ms" to gapTimeoutMs,"visual_reference_update" to "VISUAL_ONLY","max_images_per_packet" to 2,
    )
    private data class Image(val eventId: String, val assetId: String, val at: Long, val signature: VisualSignature)
    private var latest: Image? = null
    private var reference: Image? = null
    private var lastVisualAt: Long? = null
    private var lastPacketAt: Long? = null
    private var firstDigitalAt: Long? = null
    private var lastDigitalAt: Long? = null
    private val pendingDigital = linkedSetOf<String>()
    private var sequence = 0

    fun onDigital(eventId: String, at: Long): List<ObservationPacket> {
        val due = advance(at)
        require(eventId.isNotBlank())
        pendingDigital += eventId
        if (firstDigitalAt == null) firstDigitalAt = at
        lastDigitalAt = at
        return due
    }

    fun onImage(eventId: String, assetId: String, at: Long, signature: VisualSignature): List<ObservationPacket> {
        val due = advance(at)
        val image = Image(eventId, assetId, at, signature)
        latest = image
        val previous = reference
        if (previous == null || ((lastVisualAt == null || at - lastVisualAt!! >= visualCooldownMs) &&
            signature.distance(previous.signature) >= visualThreshold)) {
            return due + emit(at, setOf("VISUAL"), listOf(eventId), listOf(assetId))
        }
        return due
    }

    fun advance(at: Long): List<ObservationPacket> {
        val packets = mutableListOf<ObservationPacket>()
        if (pendingDigital.isNotEmpty()) {
            val first = requireNotNull(firstDigitalAt)
            val last = requireNotNull(lastDigitalAt)
            val due = minOf(last + digitalDebounceMs, first + digitalMaxWaitMs)
            if (due <= at) packets += emit(due, setOf("DIGITAL"))
        }
        val last = lastPacketAt
        if (last != null && at - last >= heartbeatMs) {
            val heartbeatAt = last + heartbeatMs
            if (active(heartbeatAt)) packets += emit(heartbeatAt, setOf("HEARTBEAT"))
        }
        return packets
    }

    private fun active(at: Long): Boolean = latest?.let { it.at <= at && at - it.at <= gapTimeoutMs } == true

    private fun emit(at: Long, triggers: Set<String>, eventIds: List<String> = emptyList(), visualIds: List<String> = emptyList()): ObservationPacket {
        val digital = pendingDigital.toList()
        pendingDigital.clear(); firstDigitalAt = null; lastDigitalAt = null
        val allTriggers = if (digital.isEmpty()) triggers else triggers + "DIGITAL"
        val images = if (visualIds.isNotEmpty()) visualIds else latest?.takeIf { active(at) }?.let { listOf(it.assetId) }.orEmpty()
        val packet = ObservationPacket("pkt_${(++sequence).toString().padStart(8, '0')}", at, allTriggers,
            (eventIds + digital).distinct(), images.takeLast(2), if (visualIds.isEmpty()) images else emptyList(),
            latest?.takeIf { it.at <= at }?.let { at - it.at }, !active(at))
        lastPacketAt = at
        if ("VISUAL" in allTriggers) {
            reference = latest
            lastVisualAt = at
        }
        return packet
    }
}
