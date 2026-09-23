package ai.opstate.core.hr

import ai.opstate.core.context.CausalContextStore
import ai.opstate.core.model.ObservationKind
import ai.opstate.core.model.ObservationRecord

data class HrContext(
    val newRecords: List<ObservationRecord>,
    val recentContext: List<ObservationRecord>,
    val prefetchedHistory: List<ObservationRecord>,
    val visibleIds: Set<String>,
    val omittedCount: Int,
)

/** Bounded phone-native HR context. A selected snippet remains tied to its original observation ID. */
class HrContextAssembler(private val store: CausalContextStore,
                         private val retriever: HrArchiveRetriever=PhoneLexicalArchiveRetriever(store)) {
    val backendId: String get()=retriever.backendId
    fun step(snapshotAtEpochMs: Long, runtimeAtEpochMs: Long, newEventIds: Set<String>,
             currentImageIds: Set<String>): HrContext {
        val eligible = store.causalArchiveSnapshot(snapshotAtEpochMs, runtimeAtEpochMs)
            .filter { it.kind != ObservationKind.ACTION_RECEIPT }
        val newRecords = eligible.filter { it.observationId in newEventIds }
        val recentCandidates = eligible.filter { it.kind != ObservationKind.IMAGE &&
            it.kind != ObservationKind.MODEL_NOTE &&
            (maxOf(it.availableAtEpochMs,store.exposedAt(it.observationId) ?: it.availableAtEpochMs) >=
                runtimeAtEpochMs - 6 * 60 * 60_000L) &&
            it.observationId !in newEventIds }.takeLast(128)
        val (recent, recentOmitted) = fit(recentCandidates.asReversed(), 2400)
        val (notes, noteOmitted) = fit(eligible.filter { it.kind == ObservationKind.MODEL_NOTE &&
            it.availableAtEpochMs >= snapshotAtEpochMs - 20 * 60_000L }.takeLast(1),300)
        val querySource = (newRecords + recent.take(4)).filter { it.kind != ObservationKind.IMAGE }
        val prefetch = if (querySource.isEmpty()) emptyList() else retriever.search(
            querySource.joinToString(" ") { it.content }, snapshotAtEpochMs, runtimeAtEpochMs,
            newEventIds + recent.map { it.observationId } + notes.map { it.observationId } + currentImageIds, 8)
        val (boundedPrefetch, prefetchOmitted) = fit(prefetch, 4096)
        val selected = newRecords + recent + notes + boundedPrefetch
        return HrContext(newRecords, recent + notes, boundedPrefetch,
            selected.mapTo(linkedSetOf()) { it.observationId } + currentImageIds,
            recentOmitted + noteOmitted + prefetchOmitted)
    }

    fun finalContext(original: HrContext, candidateIds: Set<String>, query: String,
                     snapshotAtEpochMs: Long, runtimeAtEpochMs: Long): HrContext {
        val retained = original.prefetchedHistory.filter { it.observationId in candidateIds }
        val found = retriever.search(query, snapshotAtEpochMs, runtimeAtEpochMs,
            original.newRecords.map { it.observationId }.toSet() +
                original.recentContext.map { it.observationId } + retained.map { it.observationId }, 8)
        val (bounded, omitted) = fit((retained + found).distinctBy { it.observationId }, 4096)
        val visible = original.newRecords.map { it.observationId } +
            original.recentContext.map { it.observationId } + bounded.map { it.observationId } +
            original.visibleIds.filter { id -> id !in original.prefetchedHistory.map { it.observationId } }
        return HrContext(original.newRecords, original.recentContext, bounded, visible.toSet(),
            original.omittedCount + omitted)
    }

    private fun fit(rows: List<ObservationRecord>, budget: Int): Pair<List<ObservationRecord>, Int> {
        val kept = mutableListOf<ObservationRecord>()
        var spent = 0
        for (row in rows) {
            // Conservative reference-token estimate. It is versioned independently of provider billing.
            val cost = row.content.codePointCount(0, row.content.length) + 24
            if (spent + cost > budget) continue
            kept += row
            spent += cost
        }
        return kept to (rows.size - kept.size)
    }
}
