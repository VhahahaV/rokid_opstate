package ai.opstate.core.episode

import ai.opstate.core.model.CandidateEpisode

/**
 * Small scenario-backed registry. It is not a general memory system. Candidate
 * episodes come from synthetic study events or explicit scenario authoring.
 */
class EpisodeRegistry {
    private val episodes = linkedMapOf<String, CandidateEpisode>()

    @Synchronized
    fun upsert(episode: CandidateEpisode) {
        require(episode.episodeHandle.isNotBlank())
        require(episode.objectHandle.isNotBlank())
        require(episode.intentFingerprint.isNotBlank())
        episodes[episode.episodeHandle] = episode
    }

    @Synchronized
    fun get(handle: String): CandidateEpisode? = episodes[handle]

    @Synchronized
    fun schedulerCandidates(): List<CandidateEpisode> = episodes.values.filter { it.schedulerEligible }

    @Synchronized
    fun updateMaterialRevision(handle: String, revision: Long) {
        val current = requireNotNull(episodes[handle]) { "unknown episode: $handle" }
        require(revision >= current.materialRevision)
        episodes[handle] = current.copy(materialRevision = revision)
    }

    @Synchronized
    fun allForAudit(): List<CandidateEpisode> = episodes.values.toList()
}
