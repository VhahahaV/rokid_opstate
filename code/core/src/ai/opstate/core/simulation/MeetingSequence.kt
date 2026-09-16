package ai.opstate.core.simulation

import ai.opstate.core.context.CausalContextStore
import ai.opstate.core.model.*
import ai.opstate.core.receipt.ReceiptPolicy
import ai.opstate.core.scheduler.AnchorPolicy
import ai.opstate.core.scheduler.AnchorScheduler

/** Four-checkpoint demonstration: early silence, service, duplicate suppression, reactivation. */
fun main() {
    val base = 1_800_000_000_000L
    val store = CausalContextStore()
    val receiptPolicy = ReceiptPolicy(receiptIdFactory = sequenceId("r"))
    val scheduler = AnchorScheduler(AnchorPolicy(), idFactory = sequenceId("a"))
    var episode = CandidateEpisode(
        episodeHandle = "ep_8f0c",
        objectHandle = "obj_19a1",
        intentFingerprint = "intent_2c91",
        serviceMode = ServiceMode.PREPARATION,
        materialRevision = 1,
        deadlineEpochMs = base + 20 * 60_000L,
    )
    store.append(
        ObservationRecord(
            "obs-change-1", ObservationKind.SYNTHETIC_CONTEXT,
            base - 1_000, base - 1_000,
            "Meeting moved to B302.", episodeHandle = episode.episodeHandle, materialRevision = 1,
        )
    )

    fun image(id: Int, novelty: Double = 0.3) = ImageQuality(
        true, 110.0, 70.0, novelty, 1280, 720, 0, "${id}".repeat(64).take(64)
    )
    fun motion(stable: Boolean, moving: Boolean = false) = MotionFeatures(
        0, 4_000, 200, 4_000,
        if (stable) 1.2 else 15.0, if (stable) 2.0 else 22.0,
        if (stable) 0.08 else 0.8, if (stable) 0.12 else 1.2,
        if (stable) 0.9 else 0.1, stable, moving, true,
    )
    fun schedule(t: Long, lastMotion: Long?, material: Boolean, postAudit: Boolean, id: Int): AnchorCandidate {
        val result = scheduler.evaluate(
            SchedulerSnapshot(
                nowEpochMs = t,
                nowElapsedMs = t - base + 100_000,
                lastCaptureElapsedMs = 0,
                lastEvaluationElapsedMs = 0,
                lastMotionSeenElapsedMs = lastMotion,
                lastMaterialChangeEpochMs = if (material) t - 4_000 else null,
                materialChangePending = material,
                motion = motion(stable = true),
                interaction = InteractionSnapshot(speechRatio = 0.0, millisSinceLastUserInteraction = 5_000),
                imageQuality = image(id),
                candidateEpisodes = listOf(episode),
                inferenceInFlight = false,
                deviceReady = true,
                thermalOrBatteryBlocked = false,
            ),
            triggerObservationId = "obs-image-$id",
            currentAssetId = "asset-$id",
            postServiceAuditEpisodeHandles = if (postAudit) setOf(episode.episodeHandle) else emptySet(),
        )
        return result.emitted.single()
    }

    val early = schedule(base, null, material = true, postAudit = false, id = 1)
    println("1 EARLY anchor=${early.anchorId} target=NO_SERVICE delivery=NO_SERVICE_SILENCE")

    val action = schedule(base + 90_000, 180_000, material = false, postAudit = false, id = 2)
    val service1 = ServiceDecision(
        "req-2", "decision-2", DecisionLabel.SERVICE, ServiceMode.PREPARATION,
        "Head to B302", "The meeting moved; leave now.", setOf("obs-change-1"),
        base + 92_000, base + 112_000,
    )
    val delivered1 = receiptPolicy.finalizeDelivery(
        service1, episode, episode.objectHandle, base + 94_000,
        store.visibleIds(base + 90_000), base + 94_000,
    )
    println("2 ACTION anchor=${action.anchorId} target=SERVICE delivery=${delivered1.status}")

    val duplicate = schedule(base + 120_000, null, material = false, postAudit = true, id = 3)
    val serviceDuplicate = service1.copy(requestId = "req-3", decisionId = "decision-3", producedAtEpochMs = base + 122_000, expiresAtEpochMs = base + 142_000)
    val suppressed = receiptPolicy.finalizeDelivery(
        serviceDuplicate, episode, episode.objectHandle, null,
        store.visibleIds(base + 120_000), base + 123_000,
    )
    println("3 DUPLICATE anchor=${duplicate.anchorId} raw=SERVICE delivery=${suppressed.status}")

    store.append(
        ObservationRecord(
            "obs-change-2", ObservationKind.SYNTHETIC_CONTEXT,
            base + 179_000, base + 179_000,
            "Meeting moved earlier by ten minutes.", episodeHandle = episode.episodeHandle, materialRevision = 2,
        )
    )
    episode = episode.copy(materialRevision = 2)
    val changed = schedule(base + 180_000, null, material = true, postAudit = false, id = 4)
    val service2 = service1.copy(
        requestId = "req-4", decisionId = "decision-4",
        evidenceObservationIds = setOf("obs-change-1", "obs-change-2"),
        producedAtEpochMs = base + 182_000, expiresAtEpochMs = base + 202_000,
    )
    val delivered2 = receiptPolicy.finalizeDelivery(
        service2, episode, episode.objectHandle, base + 184_000,
        store.visibleIds(base + 180_000), base + 184_000,
    )
    println("4 MATERIAL_CHANGE anchor=${changed.anchorId} target=SERVICE delivery=${delivered2.status}")
}

private fun sequenceId(prefix: String): () -> String {
    var n = 0
    return { "$prefix-${++n}" }
}
