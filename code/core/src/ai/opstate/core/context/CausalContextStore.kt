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
     * What one decision was given out of the record, and which tier each row came from.
     *
     * Kept because the selection decides what the model could possibly say, so a run that served on
     * nothing has to be readable back to what it was actually shown.
     */
    data class RetrievalTrace(
        val queryTerms: List<String>,
        val exactIds: List<String>,
        val recentIds: List<String>,
        val retrievedIds: List<String>,
        val pinnedIds: List<String>,
        val historicalImageIds: List<String>,
    ) {
        val allIds: List<String> get() = exactIds+recentIds+retrievedIds+pinnedIds+historicalImageIds
    }

    /**
     * HR working set: the exact packet records, one hour of recent context, a small lexical archive
     * prefetch, and the session's own memory rows.
     *
     * Every tier is a *selection*. Handing each call the whole record would mean some pair of rows can
     * always be found that appears to justify a card, which is the opposite of what a working set is for:
     * an ordinary frame should arrive with nothing relevant in it, and that emptiness is a real answer -
     * the online harness returns an empty prefetch rather than filling the quota, and the method's prompt
     * is explicit that a missing counterpart in a prefetched selection proves nothing.
     *
     * [pinnedIds] is the session's memory - the notes an imported record was chosen for. They are ranked
     * and bounded like every other tier rather than injected whole, because their older rows reach the
     * model through relevance or not at all. The fallback is recency, so a history-continuation session
     * still reaches the model with the words it was seeded with rather than photographs alone.
     */
    @Synchronized
    fun historyRetrievalDetailed(
        atEpochMs: Long,
        newEventIds: Collection<String>,
        recentWindowMs: Long = 60 * 60_000L,
        recentLimit: Int = 32,
        retrievalLimit: Int = 8,
        historicalImageLimit: Int = 4,
        pinnedIds: Collection<String> = emptySet(),
        pinnedLimit: Int = 12,
    ): Pair<List<ObservationRecord>,RetrievalTrace> {
        val visible=eventsById.values.filter {it.availableAtEpochMs<=atEpochMs}
        val records=visible.filter {it.kind!=ObservationKind.IMAGE && it.kind!=ObservationKind.ACTION_RECEIPT}
        val lowered=records.associate {it.observationId to it.content.lowercase()}
        val exact=visible.filter {it.observationId in newEventIds}
        val recent=records.filter {it.availableAtEpochMs>=atEpochMs-recentWindowMs}.takeLast(recentLimit)
        // The query comes from what this packet brought, and only from terms that can tell records apart.
        // A bare frame brings no words at all, so the query falls back to what the session is doing right
        // now; when that is nothing either, no term matches and this tier returns nothing rather than
        // padding itself with whatever happens to be near.
        val query=distinctiveTerms(exact.ifEmpty {recent.takeLast(4)}.map {it.content},lowered.values)
        val selectedIds=(exact+recent).mapTo(hashSetOf()) {it.observationId}
        fun relevance(id: String)=query.count {term->lowered.getValue(id).contains(term)}
        val retrieved=records.asSequence().filter {it.observationId !in selectedIds}
            .map {event->event to relevance(event.observationId)}
            .filter {it.second>0}
            .sortedWith(compareByDescending<Pair<ObservationRecord,Int>> {it.second}
                .thenByDescending {it.first.availableAtEpochMs}).take(retrievalLimit).map {it.first}.toList()
        val selected=(selectedIds+retrieved.map {it.observationId}).toHashSet()
        val pinned=if(pinnedIds.isEmpty()) emptyList() else records.filter {it.observationId in pinnedIds && it.observationId !in selected}
            .sortedWith(compareByDescending<ObservationRecord> {relevance(it.observationId)}
                .thenByDescending {it.availableAtEpochMs}).take(pinnedLimit)
        val historicalImages=visible.filter {it.kind==ObservationKind.IMAGE && it.observationId !in newEventIds}.takeLast(historicalImageLimit)
        val all=(exact+recent+retrieved+pinned+historicalImages).distinctBy {it.observationId}
            .sortedWith(compareBy<ObservationRecord> {it.availableAtEpochMs}.thenBy {it.observationId})
        return all to RetrievalTrace(query,exact.map {it.observationId},recent.map {it.observationId},retrieved.map {it.observationId},
            pinned.map {it.observationId},historicalImages.map {it.observationId})
    }

    @Synchronized
    fun historyRetrievalSnapshot(
        atEpochMs: Long,
        newEventIds: Collection<String>,
        recentWindowMs: Long = 60 * 60_000L,
        recentLimit: Int = 32,
        retrievalLimit: Int = 8,
        historicalImageLimit: Int = 4,
        pinnedIds: Collection<String> = emptySet(),
        pinnedLimit: Int = 12,
    ): List<ObservationRecord> = historyRetrievalDetailed(atEpochMs,newEventIds,recentWindowMs,recentLimit,retrievalLimit,
        historicalImageLimit,pinnedIds,pinnedLimit).first

    /**
     * Query terms that can tell records apart. A term carried by a large share of the record is not a
     * question about it - boilerplate observation text ("current", "image") is in every row and matches
     * everything, so a match on one of those says nothing and is not counted. A record too short to have
     * a majority is left unfiltered: with a handful of rows there is no such thing as a common term.
     */
    private fun distinctiveTerms(texts: List<String>,recordTexts: Collection<String>): List<String> {
        val candidates=texts.flatMap(::terms).distinct().take(TERM_LIMIT)
        if(recordTexts.size<COMMON_TERM_MINIMUM_RECORDS) return candidates
        return candidates.filter {term->recordTexts.count {it.contains(term)}*4<=recordTexts.size}
    }

    private fun terms(text: String): List<String> = Regex("[\\p{L}\\p{N}]{2,}").findAll(text.lowercase())
        .map {it.value}.filter {it !in STOP_TERMS}.take(32).toList()

    @Synchronized
    fun allEventsForAudit(): List<ObservationRecord> = eventsById.values.toList()

    companion object {
        private const val TERM_LIMIT=24
        private const val COMMON_TERM_MINIMUM_RECORDS=8
        private val STOP_TERMS=setOf("the","and","with","this","that","current","image","observation")
    }
}
