package ai.opstate.core.scheduler

import ai.opstate.core.model.*
import ai.opstate.core.sensor.QuietGate
import java.util.UUID

/**
 * Upstream candidate-checkpoint scheduler.
 *
 * It does not predict the service label. It determines whether the current
 * accepted observation is an appropriate checkpoint at which an already-known
 * candidate episode should be handed to the frozen OPSTATE decision model.
 */
class AnchorScheduler(
    private val policy: AnchorPolicy,
    private val quietGate: QuietGate = QuietGate(
        maxSpeechRatio = policy.maxSpeechRatio,
        minMillisSinceInteraction = policy.minMillisSinceInteraction,
        vadEnabled = true,
    ),
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private data class Evaluation(val elapsedMs: Long,val revision: Long,val intent: String)
    private val lastByEpisode=mutableMapOf<String,Evaluation>()
    fun reset() { lastByEpisode.clear() }

    fun evaluate(
        snapshot: SchedulerSnapshot,
        triggerObservationId: String,
        currentAssetId: String,
        postServiceAuditEpisodeHandles: Set<String> = emptySet(),
    ): AnchorSchedulingResult {
        val globalBlockers = linkedSetOf<AnchorBlocker>()
        if (!snapshot.deviceReady) globalBlockers += AnchorBlocker.DEVICE_NOT_READY
        if (snapshot.thermalOrBatteryBlocked) globalBlockers += AnchorBlocker.THERMAL_OR_BATTERY
        if (snapshot.inferenceInFlight) globalBlockers += AnchorBlocker.INFERENCE_IN_FLIGHT
        globalBlockers += quietGate.blockers(snapshot.interaction)

        val image = snapshot.imageQuality
        if (image == null || !image.valid || image.meanLuma !in policy.minImageLuma..policy.maxImageLuma ||
            image.blurScore < policy.minBlurScore
        ) {
            globalBlockers += AnchorBlocker.IMAGE_INVALID
        }

        val sinceLastEvaluation = snapshot.lastEvaluationElapsedMs?.let { snapshot.nowElapsedMs - it }
            ?: Long.MAX_VALUE

        val eligibleEpisodes = snapshot.candidateEpisodes.filter { it.schedulerEligible }
        if (eligibleEpisodes.isEmpty()) globalBlockers += AnchorBlocker.NO_CANDIDATE_EPISODE

        // A checkpoint may be captured while moving, but model evaluation waits
        // for a micro-stable window unless the policy is explicitly relaxed.
        val motion = snapshot.motion
        if (policy.requireStablePose && (motion == null || !motion.valid || !motion.isStable)) {
            globalBlockers += AnchorBlocker.MOTION_HIGH
        }

        if (globalBlockers.isNotEmpty()) {
            return AnchorSchedulingResult(emptyList(), globalBlockers, emptyMap())
        }

        val scores = linkedMapOf<String, Double>()
        val candidates = mutableListOf<AnchorCandidate>()
        val perEpisodeBlockers=linkedMapOf<String,Set<AnchorBlocker>>()
        eligibleEpisodes.forEach { episode ->
            val previous=lastByEpisode[episode.episodeHandle]
            val age=previous?.let {snapshot.nowElapsedMs-it.elapsedMs} ?: Long.MAX_VALUE
            val materialChanged=(snapshot.materialChangePending &&
                (snapshot.materialChangedEpisodeHandles?.contains(episode.episodeHandle) ?: true)) ||
                (previous!=null && (episode.materialRevision>previous.revision || episode.intentFingerprint!=previous.intent))
            val audit=episode.episodeHandle in postServiceAuditEpisodeHandles
            val blockers=linkedSetOf<AnchorBlocker>()
            if(!materialChanged && !audit) {
                if(sinceLastEvaluation<policy.minEvaluationIntervalMs) blockers+=AnchorBlocker.EVALUATION_COOLDOWN
                if(age<policy.minEpisodeIntervalMs) blockers+=AnchorBlocker.EPISODE_COOLDOWN
            }
            // Routine window checks use their own longer budget. A directed post-service audit is a separate check.
            if(episode.routineWindowActive && !audit && age<policy.minRoutineIntervalMs) blockers+=AnchorBlocker.ROUTINE_COOLDOWN
            if(blockers.isNotEmpty()) {perEpisodeBlockers[episode.episodeHandle]=blockers;return@forEach}
            val families = linkedSetOf<AnchorTriggerFamily>()
            var score = policy.scoreActiveCandidate

            if (materialChanged) {
                score += policy.scoreMaterialChange
                families += AnchorTriggerFamily.MATERIAL_CHANGE
            }

            val motionRecentlySeen = snapshot.lastMotionSeenElapsedMs?.let {
                snapshot.nowElapsedMs - it <= 20_000L
            } == true
            if (policy.allowTransitionSettled && motion?.isStable == true && motionRecentlySeen) {
                score += policy.scoreTransitionSettled
                families += AnchorTriggerFamily.TRANSITION_SETTLED
            } else if (motion?.isStable == true && motion.durationMs >= 3_000L) {
                families += AnchorTriggerFamily.STABLE_DWELL
            }

            episode.deadlineEpochMs?.let { deadline ->
                if (deadline - snapshot.nowEpochMs in 0..30 * 60_000L) {
                    score += policy.scoreDeadline
                    families += AnchorTriggerFamily.DEADLINE_APPROACH
                }
            }
            if (episode.routineWindowActive) {
                score += policy.scoreRoutine
                families += AnchorTriggerFamily.ROUTINE_WINDOW
            }
            if (policy.allowVisualNovelty && image != null && image.noveltyScore >= policy.minVisualNovelty) {
                score += policy.scoreVisualNovelty
                families += AnchorTriggerFamily.VISUAL_NOVELTY
            }
            val coverage=policy.allowPeriodicCoverage && age>=policy.periodicCoverageIntervalMs
            if (coverage) {
                score += policy.scorePeriodic
                families += AnchorTriggerFamily.PERIODIC_COVERAGE
            }
            if (audit) {
                score += policy.scorePostServiceAudit
                families += AnchorTriggerFamily.POST_SERVICE_AUDIT
            }

            val triggerMatch = episode.triggerKeys.isEmpty() ||
                episode.triggerKeys.any { it in snapshot.visualTriggerKeys }
            if (!triggerMatch && policy.mode == SchedulerMode.DEPLOYMENT_OPTIMIZED) {
                score *= 0.35
            }

            val boundedScore = score.coerceIn(0.0, 1.0)
            scores[episode.episodeHandle] = boundedScore
            // Keep waiting/already-served/unchanged checkpoints in the research profile.
            // Coverage bypasses only the soft score, never motion, interaction, device or cooldown gates.
            val preserveNegative=policy.mode==SchedulerMode.BENCHMARK_ALIGNED && (coverage || audit)
            if (boundedScore >= policy.emitThreshold || preserveNegative) {
                candidates += AnchorCandidate(
                    anchorId = "anchor-${idFactory()}",
                    anchorTimeEpochMs = snapshot.nowEpochMs,
                    triggerObservationId = triggerObservationId,
                    currentAssetId = currentAssetId,
                    candidateObjectHandle = episode.objectHandle,
                    candidateEpisodeHandle = episode.episodeHandle,
                    triggerFamilies = families,
                    score = boundedScore,
                    schedulerDiagnostics = mapOf(
                        "mode" to policy.mode.name,
                        "motionStable" to (motion?.isStable == true).toString(),
                        "speechRatio" to snapshot.interaction.speechRatio.toString(),
                        "materialChangePending" to materialChanged.toString(),
                        "periodicCoverageDue" to coverage.toString(),
                        "negativeCoverageOverride" to (preserveNegative && boundedScore<policy.emitThreshold).toString(),
                        "visualNovelty" to (image?.noveltyScore ?: 0.0).toString(),
                        "triggerMatch" to triggerMatch.toString(),
                        "episodeLifecyclePrivate" to episode.lifecycle.name,
                    ),
                )
            }
        }

        val selected=candidates.sortedWith(compareByDescending<AnchorCandidate> {it.score}
            .thenBy {lastByEpisode[it.candidateEpisodeHandle]?.elapsedMs ?: Long.MIN_VALUE})
            .take(policy.maxAnchorsPerObservation)
        val episodes=eligibleEpisodes.associateBy {it.episodeHandle}
        selected.forEach {anchor ->
            val episode=episodes.getValue(anchor.candidateEpisodeHandle)
            lastByEpisode[episode.episodeHandle]=Evaluation(snapshot.nowElapsedMs,episode.materialRevision,episode.intentFingerprint)
        }
        return AnchorSchedulingResult(
            emitted=selected,
            blockers=if(selected.isEmpty()) perEpisodeBlockers.values.flatten().toSet() else emptySet(),
            scoreByEpisode=scores,
            blockersByEpisode=perEpisodeBlockers,
        )
    }
}
