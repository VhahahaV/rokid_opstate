package ai.opstate.core.episode

import ai.opstate.core.model.CandidateEpisode

/**
 * Small lookup index for scenario-backed candidate episodes. Trigger keys route
 * candidates; they do not authorize a service and are not target-model inputs.
 */
class TriggerIndex {
    private val byKey = linkedMapOf<String, LinkedHashSet<String>>()
    private val always = linkedSetOf<String>()
    private val episodes = linkedMapOf<String, CandidateEpisode>()

    @Synchronized
    fun rebuild(source: List<CandidateEpisode>) {
        byKey.clear()
        always.clear()
        episodes.clear()
        source.forEach { episode ->
            episodes[episode.episodeHandle] = episode
            if (episode.triggerKeys.isEmpty()) {
                always += episode.episodeHandle
            } else {
                episode.triggerKeys.forEach { key ->
                    byKey.getOrPut(key) { linkedSetOf() } += episode.episodeHandle
                }
            }
        }
    }

    @Synchronized
    fun query(keys: Set<String>, includeUnmatchedForCoverage: Boolean): List<CandidateEpisode> {
        val handles = linkedSetOf<String>()
        handles += always
        keys.forEach { key -> handles += byKey[key].orEmpty() }
        if (includeUnmatchedForCoverage) handles += episodes.keys
        return handles.mapNotNull(episodes::get).filter { it.schedulerEligible }
    }
}
