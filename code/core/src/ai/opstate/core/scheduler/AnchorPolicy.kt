package ai.opstate.core.scheduler

/**
 * BENCHMARK_ALIGNED intentionally over-generates checkpoints and leaves
 * SERVICE/NO_SERVICE, lifecycle, completion and receipt reasoning to the target
 * decision stack. DEPLOYMENT_OPTIMIZED may later add stronger pre-model gates.
 */
enum class SchedulerMode { BENCHMARK_ALIGNED, DEPLOYMENT_OPTIMIZED }

data class AnchorPolicy(
    val mode: SchedulerMode = SchedulerMode.BENCHMARK_ALIGNED,
    val minEvaluationIntervalMs: Long = 30_000L,
    val minEpisodeIntervalMs: Long = 90_000L,
    val minRoutineIntervalMs: Long = 600_000L,
    val periodicCoverageIntervalMs: Long = 90_000L,
    val maxSpeechRatio: Double = 0.35,
    val minMillisSinceInteraction: Long = 2_500L,
    val requireStablePose: Boolean = true,
    val allowTransitionSettled: Boolean = true,
    val allowPeriodicCoverage: Boolean = true,
    val allowVisualNovelty: Boolean = true,
    val minVisualNovelty: Double = 0.12,
    val minImageLuma: Double = 18.0,
    val maxImageLuma: Double = 238.0,
    val minBlurScore: Double = 25.0,
    val emitThreshold: Double = 0.45,
    val maxAnchorsPerObservation: Int = 3,
    val scoreActiveCandidate: Double = 0.30,
    val scoreMaterialChange: Double = 0.25,
    val scoreTransitionSettled: Double = 0.20,
    val scoreDeadline: Double = 0.15,
    val scoreRoutine: Double = 0.10,
    val scoreVisualNovelty: Double = 0.10,
    val scorePeriodic: Double = 0.05,
    val scorePostServiceAudit: Double = 0.10,
) {
    fun configuration(): Map<String,Any> = linkedMapOf(
        "mode" to mode.name,"min_evaluation_interval_ms" to minEvaluationIntervalMs,"min_episode_interval_ms" to minEpisodeIntervalMs,
        "min_routine_interval_ms" to minRoutineIntervalMs,"periodic_coverage_interval_ms" to periodicCoverageIntervalMs,
        "max_speech_ratio" to maxSpeechRatio,"min_millis_since_interaction" to minMillisSinceInteraction,"require_stable_pose" to requireStablePose,
        "allow_transition_settled" to allowTransitionSettled,"allow_periodic_coverage" to allowPeriodicCoverage,"allow_visual_novelty" to allowVisualNovelty,
        "min_visual_novelty" to minVisualNovelty,"min_image_luma" to minImageLuma,"max_image_luma" to maxImageLuma,"min_blur_score" to minBlurScore,
        "emit_threshold" to emitThreshold,"max_anchors_per_observation" to maxAnchorsPerObservation,
        "score_active_candidate" to scoreActiveCandidate,"score_material_change" to scoreMaterialChange,"score_transition_settled" to scoreTransitionSettled,
        "score_deadline" to scoreDeadline,"score_routine" to scoreRoutine,"score_visual_novelty" to scoreVisualNovelty,
        "score_periodic" to scorePeriodic,"score_post_service_audit" to scorePostServiceAudit,
    )
}
