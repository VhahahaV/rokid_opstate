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
    private val exposedById = hashMapOf<String, Long>()
    private val sourceIdsById = hashMapOf<String, Set<String>>()

    @Synchronized
    fun append(event: ObservationRecord, exposedAtEpochMs: Long = event.availableAtEpochMs,
               sourceIds: Set<String> = setOf(event.observationId)) {
        require(event.availableAtEpochMs >= event.occurredAtEpochMs) {
            "availableAtEpochMs must not precede occurredAtEpochMs"
        }
        require(!eventsById.containsKey(event.observationId)) {
            "duplicate observationId: ${event.observationId}"
        }
        require(exposedAtEpochMs >= event.availableAtEpochMs) { "exposure precedes availability" }
        eventsById[event.observationId] = event
        exposedById[event.observationId] = exposedAtEpochMs
        sourceIdsById[event.observationId] = sourceIds
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
    fun remove(observationId: String): ObservationRecord? {
        val removed=eventsById[observationId] ?: return null
        // A model note may describe a photo that was later deleted, and a future derived row may
        // cite that note. Remove the whole descendant chain from the *working set*; the journal stays
        // append-only and retains the original rows plus the source deletion tombstone for audit.
        val withdrawn=linkedSetOf(observationId)
        while(true) {
            val dependent=sourceIdsById.entries.filter { (id,sources)->
                id !in withdrawn && sources.any {it in withdrawn}
            }.map {it.key}
            if(dependent.isEmpty()) break
            withdrawn.addAll(dependent)
        }
        withdrawn.forEach {id->eventsById.remove(id);exposedById.remove(id);sourceIdsById.remove(id)}
        return removed
    }

    /** Native phone archive search. Eligibility is checked before ranking, even for precomputed indexes. */
    @Synchronized
    fun searchArchive(
        query: String,
        snapshotAtEpochMs: Long,
        runtimeAtEpochMs: Long,
        excludeIds: Set<String> = emptySet(),
        limit: Int = 8,
    ): List<ObservationRecord> {
        if (query.isBlank() || limit <= 0) return emptyList()
        val terms = archiveTerms(query)
        if (terms.isEmpty()) return emptyList()
        val exact = Regex("[A-Za-z0-9]+(?:[-_:/.][A-Za-z0-9]+)+").findAll(query).map { it.value.lowercase() }.toSet()
        return eventsById.values.asSequence()
            .filter { it.observationId !in excludeIds && it.kind != ObservationKind.IMAGE &&
                it.kind != ObservationKind.MODEL_NOTE &&
                it.kind != ObservationKind.ACTION_RECEIPT && it.availableAtEpochMs <= snapshotAtEpochMs &&
                (exposedById[it.observationId] ?: Long.MAX_VALUE) <= runtimeAtEpochMs }
            .map { record ->
                val content = record.content.lowercase()
                val hits = archiveTerms(content).toSet()
                val shared = terms.count { it in hits }
                val exactBoost = exact.count { it in content }
                record to (shared + 5 * exactBoost)
            }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<ObservationRecord, Int>> { it.second }
                .thenByDescending { it.first.availableAtEpochMs }.thenBy { it.first.observationId })
            .take(limit).map { it.first }.toList()
    }

    /** Latin words, entity IDs and overlapping Han bigrams; no dependency on workbench FTS5. */
    private fun archiveTerms(text: String): Set<String> {
        val lowered = text.lowercase()
        val latin = Regex("[a-z0-9]+(?:[-_:/.][a-z0-9]+)*").findAll(lowered)
            .map { it.value }.filter { it.length >= 2 }.toList()
        val han = Regex("[\\p{IsHan}]+").findAll(lowered).flatMap { match ->
            val value = match.value
            if (value.length == 1) sequenceOf(value)
            else (0 until value.length - 1).asSequence().map { value.substring(it, it + 2) }
        }.toList()
        return (latin + han).take(128).toSet()
    }

    @Synchronized
    fun causalArchiveSnapshot(snapshotAtEpochMs: Long, runtimeAtEpochMs: Long): List<ObservationRecord> =
        eventsById.values.filter { it.availableAtEpochMs <= snapshotAtEpochMs &&
            (exposedById[it.observationId] ?: Long.MAX_VALUE) <= runtimeAtEpochMs }

    @Synchronized
    fun exposedAt(observationId: String): Long? = exposedById[observationId]

    @Synchronized
    fun sourceIds(observationId: String): Set<String> = sourceIdsById[observationId].orEmpty()

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
