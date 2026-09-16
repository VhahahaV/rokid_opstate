package ai.opstate.core.runtime

import java.security.MessageDigest

/** Minimal append-only hash chain. Persist each returned entry transactionally. */
class AuditChain {
    data class Entry(
        val sequence: Long,
        val eventType: String,
        val eventAtEpochMs: Long,
        val payloadSha256: String,
        val previousChainSha256: String,
        val chainSha256: String,
    )

    private val entries = mutableListOf<Entry>()

    @Synchronized
    fun append(eventType: String, eventAtEpochMs: Long, canonicalPayload: ByteArray): Entry {
        val previous = entries.lastOrNull()?.chainSha256 ?: ZERO_HASH
        val payload = sha256(canonicalPayload)
        val sequence = entries.size.toLong()
        val chain = sha256("$sequence|$eventType|$eventAtEpochMs|$payload|$previous".encodeToByteArray())
        return Entry(sequence, eventType, eventAtEpochMs, payload, previous, chain).also(entries::add)
    }

    @Synchronized
    fun verify(candidate: List<Entry> = entries): Boolean {
        var previous = ZERO_HASH
        candidate.forEachIndexed { index, entry ->
            if (entry.sequence != index.toLong()) return false
            if (entry.previousChainSha256 != previous) return false
            val expected = sha256(
                "${entry.sequence}|${entry.eventType}|${entry.eventAtEpochMs}|${entry.payloadSha256}|$previous"
                    .encodeToByteArray()
            )
            if (expected != entry.chainSha256) return false
            previous = entry.chainSha256
        }
        return true
    }

    @Synchronized fun all(): List<Entry> = entries.toList()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    companion object { val ZERO_HASH: String = "0".repeat(64) }
}
