package ai.opstate.core.runtime

import ai.opstate.core.context.CausalContextStore
import ai.opstate.core.episode.EpisodeRegistry
import ai.opstate.core.model.CandidateEpisode
import ai.opstate.core.model.ObservationRecord

/**
 * Deterministic virtual-time scenario materializer. Public events are revealed
 * when virtual time reaches availableAt; authoring Gold is not represented here.
 */
class ScenarioRuntime(
    private val contextStore: CausalContextStore,
    private val episodeRegistry: EpisodeRegistry,
) {
    data class Scenario(
        val scenarioId: String,
        val zeroEpochMs: Long,
        val events: List<ObservationRecord>,
        val episodes: List<CandidateEpisode>,
    )

    private var scenario: Scenario? = null
    private var virtualNowEpochMs: Long = 0L
    private val revealed = linkedSetOf<String>()

    @Synchronized
    fun load(value: Scenario) {
        scenario = value
        virtualNowEpochMs = value.zeroEpochMs
        revealed.clear()
        value.episodes.forEach(episodeRegistry::upsert)
        revealAvailable()
    }

    @Synchronized
    fun advanceTo(epochMs: Long): List<ObservationRecord> {
        require(epochMs >= virtualNowEpochMs) { "virtual time cannot move backwards" }
        virtualNowEpochMs = epochMs
        return revealAvailable()
    }

    @Synchronized fun nowEpochMs(): Long = virtualNowEpochMs

    private fun revealAvailable(): List<ObservationRecord> {
        val current = scenario ?: return emptyList()
        val newEvents = current.events
            .filter { it.availableAtEpochMs <= virtualNowEpochMs && it.observationId !in revealed }
            .sortedWith(compareBy<ObservationRecord> { it.availableAtEpochMs }.thenBy { it.observationId })
        newEvents.forEach { event ->
            contextStore.append(event)
            revealed += event.observationId
            val revision = event.materialRevision
            val handle = event.episodeHandle
            if (revision != null && handle != null && episodeRegistry.get(handle) != null) {
                episodeRegistry.updateMaterialRevision(handle, revision)
            }
        }
        return newEvents
    }
}
