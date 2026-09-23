package ai.opstate.core.hr

import ai.opstate.core.context.CausalContextStore
import ai.opstate.core.model.ObservationRecord

/** A backend ranks only rows that pass both availability and exposure-time checks. */
interface HrArchiveRetriever {
    val backendId: String
    fun search(query: String,snapshotAtEpochMs: Long,runtimeAtEpochMs: Long,
               excludeIds: Set<String>,limit: Int): List<ObservationRecord>
}

class PhoneLexicalArchiveRetriever(private val store: CausalContextStore) : HrArchiveRetriever {
    override val backendId="PHONE_LEXICAL_V1"
    override fun search(query: String,snapshotAtEpochMs: Long,runtimeAtEpochMs: Long,
                        excludeIds: Set<String>,limit: Int): List<ObservationRecord> =
        store.searchArchive(query,snapshotAtEpochMs,runtimeAtEpochMs,excludeIds,limit)
}
