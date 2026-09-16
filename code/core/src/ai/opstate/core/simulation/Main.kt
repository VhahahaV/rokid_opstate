package ai.opstate.core.simulation

import ai.opstate.core.context.CausalContextStore
import ai.opstate.core.inference.InferenceContract
import ai.opstate.core.inference.InferenceResponse
import ai.opstate.core.model.*
import ai.opstate.core.receipt.ReceiptPolicy
import ai.opstate.core.scheduler.AnchorPolicy
import ai.opstate.core.scheduler.AnchorScheduler

fun main() {
    val now = 1_800_000_000_000L
    val context = CausalContextStore()
    context.append(
        ObservationRecord(
            observationId = "obs-message-move",
            kind = ObservationKind.SYNTHETIC_CONTEXT,
            occurredAtEpochMs = now - 10 * 60_000,
            availableAtEpochMs = now - 10 * 60_000,
            content = "The meeting was moved to room B302.",
            episodeHandle = "ep_8f0c",
            materialRevision = 1,
        )
    )
    val episode = CandidateEpisode(
        episodeHandle = "ep_8f0c",
        objectHandle = "obj_19a1",
        intentFingerprint = "intent_2c91",
        serviceMode = ServiceMode.PREPARATION,
        materialRevision = 1,
        deadlineEpochMs = now + 12 * 60_000,
    )
    val image = ImageQuality(true, 112.0, 80.0, 0.41, 1280, 720, 0, "sha256-demo")
    val scheduler = AnchorScheduler(AnchorPolicy(), idFactory = { "demo" })
    val scheduling = scheduler.evaluate(
        snapshot = SchedulerSnapshot(
            nowEpochMs = now,
            nowElapsedMs = 120_000,
            lastCaptureElapsedMs = 60_000,
            lastEvaluationElapsedMs = 0,
            lastMotionSeenElapsedMs = 112_000,
            lastMaterialChangeEpochMs = now - 10 * 60_000,
            materialChangePending = false,
            motion = MotionFeatures(116_000, 120_000, 200, 4_000, 1.2, 2.0, 0.08, 0.12, 0.9, true, false, true),
            interaction = InteractionSnapshot(speechRatio = 0.0, millisSinceLastUserInteraction = 10_000),
            imageQuality = image,
            candidateEpisodes = listOf(episode),
            inferenceInFlight = false,
            deviceReady = true,
            thermalOrBatteryBlocked = false,
        ),
        triggerObservationId = "obs-image-120",
        currentAssetId = "asset-image-120",
    )
    val anchor = scheduling.emitted.single()
    val request = InferenceContract.buildRequest(
        requestId = "req-demo",
        anchor = anchor,
        image = image,
        causalEvents = context.causalSnapshot(now, episode.episodeHandle),
        receipts = emptyList(),
        promptHash = "prompt-hash",
        preprocessHash = "preprocess-hash",
    )
    val response = InferenceResponse(
        requestId = request.requestId,
        decisionId = "decision-demo",
        decision = DecisionLabel.SERVICE,
        serviceMode = ServiceMode.PREPARATION,
        title = "Leave for B302",
        body = "The meeting moved to B302; leaving now gives enough walking time.",
        evidenceObservationIds = setOf("obs-message-move"),
        producedAtEpochMs = now + 2_000,
        expiresAtEpochMs = now + 22_000,
    )
    check(InferenceContract.validate(response, request).isEmpty())
    val outcome = ReceiptPolicy(receiptIdFactory = { "demo" }).finalizeDelivery(
        decision = InferenceContract.toDecision(response),
        episode = episode,
        objectHandle = episode.objectHandle,
        visibleAckEpochMs = now + 4_000,
        visibleEvidenceIds = context.visibleIds(now),
        nowEpochMs = now + 4_000,
    )
    println("anchor=${anchor.anchorId} score=${anchor.score} delivery=${outcome.status} receipt=${outcome.receipt?.receiptId}")
}
