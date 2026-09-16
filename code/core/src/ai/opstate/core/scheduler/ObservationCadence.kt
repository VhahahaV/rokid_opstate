package ai.opstate.core.scheduler

import ai.opstate.core.model.CapturePlan
import ai.opstate.core.model.CaptureReason
import ai.opstate.core.model.SchedulerSnapshot

/**
 * Exact one-minute capture must be driven by a foreground runtime/ticker, not
 * periodic WorkManager. The baseline is augmented by event-driven captures.
 */
class ObservationCadence(
    private val baselineIntervalMs: Long = 60_000L,
    private val maxStalenessMs: Long = 90_000L,
    private val minCaptureIntervalMs: Long = 15_000L,
    private val materialChangeDelayMs: Long = 3_000L,
    private val transitionLookbackMs: Long = 20_000L,
) {
    fun configuration(): Map<String,Long> = mapOf(
        "baseline_interval_ms" to baselineIntervalMs,"max_staleness_ms" to maxStalenessMs,
        "min_capture_interval_ms" to minCaptureIntervalMs,"material_change_delay_ms" to materialChangeDelayMs,
        "transition_lookback_ms" to transitionLookbackMs,
    )
    fun plan(snapshot: SchedulerSnapshot): CapturePlan {
        val lastCapture = snapshot.lastCaptureElapsedMs
        val elapsed = if (lastCapture == null) Long.MAX_VALUE else snapshot.nowElapsedMs - lastCapture
        val reasons = linkedSetOf<CaptureReason>()

        if (snapshot.lastBaselineCaptureElapsedMs == null || snapshot.nowElapsedMs-snapshot.lastBaselineCaptureElapsedMs >= baselineIntervalMs) {
            reasons += CaptureReason.PERIODIC_BASELINE
        }
        if (elapsed >= maxStalenessMs) reasons += CaptureReason.MAX_STALENESS

        val materialChangeAge = snapshot.lastMaterialChangeEpochMs?.let { snapshot.nowEpochMs - it }
        if (snapshot.materialChangePending && materialChangeAge != null && materialChangeAge >= materialChangeDelayMs) {
            reasons += CaptureReason.MATERIAL_CHANGE
        }

        val motion = snapshot.motion
        val motionRecentlySeen = snapshot.lastMotionSeenElapsedMs?.let {
            snapshot.nowElapsedMs - it <= transitionLookbackMs
        } == true
        if (motion?.valid == true && motion.isStable && motionRecentlySeen) {
            reasons += CaptureReason.TRANSITION_SETTLED
        }
        if (snapshot.routineCapturePending) reasons += CaptureReason.ROUTINE_WINDOW
        if (snapshot.deadlineCapturePending) reasons += CaptureReason.DEADLINE_WINDOW
        if (snapshot.postServiceAuditPending) reasons += CaptureReason.POST_SERVICE_AUDIT

        val cooldownSatisfied = elapsed >= minCaptureIntervalMs
        val due = reasons.isNotEmpty() && cooldownSatisfied && snapshot.deviceReady && !snapshot.thermalOrBatteryBlocked
        val earliest = if (lastCapture == null) snapshot.nowElapsedMs
        else lastCapture + minCaptureIntervalMs

        return CapturePlan(
            due = due,
            reasons = reasons,
            earliestAtElapsedMs = earliest,
            forceEvenWithoutCandidate = CaptureReason.PERIODIC_BASELINE in reasons ||
                CaptureReason.MAX_STALENESS in reasons,
        )
    }
}
