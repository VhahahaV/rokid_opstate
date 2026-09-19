package ai.opstate.core.context

import ai.opstate.core.model.ObservationKind
import ai.opstate.core.model.ObservationRecord

/**
 * Append-only causal event store. availableAt, not occurredAt, controls model
 * visibility. This prevents late captions or delayed synthetic events from
 * affecting an earlier decision.
 */
class CausalContextStore {
    private val eventsById = linkedMapOf<String, ObservationRecord>()

    @Synchronized
    fun append(event: ObservationRecord) {
        require(event.availableAtEpochMs >= event.occurredAtEpochMs) {
            "availableAtEpochMs must not precede occurredAtEpochMs"
        }
        require(!eventsById.containsKey(event.observationId)) {
            "duplicate observationId: ${event.observationId}"
        }
        eventsById[event.observationId] = event
    }

    /**
     * Drops one event from the working set.
     *
     * This store is what a *running* session may still read, not the record itself: the journal keeps
     * every observation it ever accepted, including a photograph an operator removed afterwards.
     * Removing it here is what stops a later request from citing it, which is the point of removing it.
     * Returns the dropped event, or null when it was not held.
     */
    @Synchronized
    fun remove(observationId: String): ObservationRecord? = eventsById.remove(observationId)

    @Synchronized
    fun causalSnapshot(
        atEpochMs: Long,
        candidateEpisodeHandle: String? = null,
        maxEvents: Int = 200,
    ): List<ObservationRecord> = eventsById.values
        .asSequence()
        .filter { it.availableAtEpochMs <= atEpochMs }
        .filter {
            candidateEpisodeHandle == null ||
                it.episodeHandle == null ||
                it.episodeHandle == candidateEpisodeHandle ||
                it.kind == ObservationKind.ACTION_RECEIPT
        }
        .sortedWith(compareBy<ObservationRecord> { it.availableAtEpochMs }.thenBy { it.observationId })
        .toList()
        .takeLast(maxEvents)

    @Synchronized
    fun visibleIds(atEpochMs: Long): Set<String> = eventsById.values
        .filter { it.availableAtEpochMs <= atEpochMs }
        .mapTo(linkedSetOf()) { it.observationId }

    /**
     * HR working set: the exact packet records, one hour of recent context, a small lexical archive
     * prefetch, and anything the caller pins.
     *
     * [pinnedIds] exists because of imported history. A session seeded from an earlier collection
     * holds the notes the operator chose as this session's memory, and every other selector here
     * misses them: they are older than any sane recency window, and the lexical prefetch scores them
     * against terms drawn from the current observation's boilerplate content, which a note written in
     * another language can never match. Without pinning, a history-continuation session reaches the
     * model with historical photographs and no words at all.
     */
    @Synchronized
    fun historyRetrievalSnapshot(
        atEpochMs: Long,
        newEventIds: Collection<String>,
        recentWindowMs: Long = 60 * 60_000L,
        recentLimit: Int = 128,
        retrievalLimit: Int = 8,
        historicalImageLimit: Int = 4,
        pinnedIds: Collection<String> = emptySet(),
    ): List<ObservationRecord> {
        val visible=eventsById.values.filter {it.availableAtEpochMs<=atEpochMs}
        val exact=visible.filter {it.observationId in newEventIds}
        val recent=visible.filter {it.kind!=ObservationKind.IMAGE && it.availableAtEpochMs>=atEpochMs-recentWindowMs}.takeLast(recentLimit)
        val query=(exact+recent.takeLast(12)).flatMap {terms(it.content)}.toSet()
        val selectedIds=(exact+recent).mapTo(hashSetOf()) {it.observationId}
        val retrieved=visible.asSequence().filter {it.kind!=ObservationKind.IMAGE && it.observationId !in selectedIds}
            .map {event->event to query.count {term->event.content.lowercase().contains(term)}}
            .filter {it.second>0}.sortedWith(compareByDescending<Pair<ObservationRecord,Int>> {it.second}
                .thenByDescending {it.first.availableAtEpochMs}).take(retrievalLimit).map {it.first}.toList()
        val pinned=if(pinnedIds.isEmpty()) emptyList() else visible.filter {it.observationId in pinnedIds}
        val historicalImages=visible.filter {it.kind==ObservationKind.IMAGE && it.observationId !in newEventIds}.takeLast(historicalImageLimit)
        return (exact+recent+retrieved+pinned+historicalImages).distinctBy {it.observationId}
            .sortedWith(compareBy<ObservationRecord> {it.availableAtEpochMs}.thenBy {it.observationId})
    }

    private fun terms(text: String): List<String> = Regex("[\\p{L}\\p{N}]{2,}").findAll(text.lowercase())
        .map {it.value}.filter {it !in STOP_TERMS}.take(32).toList()

    @Synchronized
    fun allEventsForAudit(): List<ObservationRecord> = eventsById.values.toList()

    companion object {private val STOP_TERMS=setOf("the","and","with","this","that","current","image","observation")}
}
