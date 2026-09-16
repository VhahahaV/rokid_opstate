package ai.opstate.core.tests

import ai.opstate.core.context.CausalContextStore
import ai.opstate.core.inference.*
import ai.opstate.core.model.*
import ai.opstate.core.receipt.ReceiptPolicy
import ai.opstate.core.runtime.StudyStateMachine
import ai.opstate.core.scheduler.*
import ai.opstate.core.sensor.MotionFeatureExtractor
import ai.opstate.core.quality.ImageQualityStatistics
import kotlin.math.cos
import kotlin.math.sin

private fun assertTrue(value: Boolean, message: String) { if (!value) error(message) }
private fun assertFalse(value: Boolean, message: String) = assertTrue(!value, message)
private fun assertEquals(expected: Any?, actual: Any?, message: String = "") {
    if (expected != actual) error("$message expected=$expected actual=$actual")
}

private fun qYaw(degrees: Double): Quaternion {
    val half = Math.toRadians(degrees) / 2.0
    return Quaternion(0.0, 0.0, sin(half), cos(half))
}

private fun stableSamples(): List<SensorSample> = (0 until 200).map { i ->
    SensorSample(
        elapsedRealtimeNanos = i * 20_000_000L,
        rotation = qYaw(i * 0.002),
        linearAccelerationMps2 = Vector3(0.02, 0.01, 0.01),
    )
}

private fun movingSamples(): List<SensorSample> = (0 until 200).map { i ->
    SensorSample(
        elapsedRealtimeNanos = i * 20_000_000L,
        rotation = qYaw(i * 0.8),
        linearAccelerationMps2 = Vector3(0.7, 0.4, 0.2),
    )
}

private fun baseSnapshot(now: Long, episode: CandidateEpisode, image: ImageQuality): SchedulerSnapshot =
    SchedulerSnapshot(
        nowEpochMs = now,
        nowElapsedMs = 100_000,
        lastCaptureElapsedMs = 40_000,
        lastEvaluationElapsedMs = 0,
        lastMotionSeenElapsedMs = 90_000,
        lastMaterialChangeEpochMs = now - 4_000,
        materialChangePending = true,
        motion = MotionFeatureExtractor().extract(stableSamples()),
        interaction = InteractionSnapshot(speechRatio = 0.0, millisSinceLastUserInteraction = 10_000),
        imageQuality = image,
        candidateEpisodes = listOf(episode),
        inferenceInFlight = false,
        deviceReady = true,
        thermalOrBatteryBlocked = false,
        visualTriggerKeys = setOf("surface:door"),
    )

fun main() {
    var passed = 0
    fun test(name: String, body: () -> Unit) {
        body()
        passed++
        println("PASS $name")
    }

    test("motion stable and moving classification") {
        val extractor = MotionFeatureExtractor()
        assertTrue(extractor.extract(stableSamples()).isStable, "stable window should be stable")
        assertTrue(extractor.extract(movingSamples()).isMoving, "moving window should be moving")
    }

    test("one-minute cadence plus event capture") {
        val now = 100_000L
        val episode = CandidateEpisode("ep", "obj", "intent", ServiceMode.AWARENESS)
        val image = ImageQuality(true, 100.0, 80.0, 0.2, 1280, 720, 0, "sha")
        val cadence = ObservationCadence()
        val plan = cadence.plan(baseSnapshot(now, episode, image))
        assertTrue(plan.due, "baseline/event capture should be due")
        assertTrue(CaptureReason.PERIODIC_BASELINE in plan.reasons, "missing periodic reason")
        assertTrue(CaptureReason.MATERIAL_CHANGE in plan.reasons, "missing material-change reason")
    }

    test("anchor scheduler emits checkpoint but not label") {
        val now = 1_800_000_000_000L
        val episode = CandidateEpisode(
            "ep", "obj", "intent", ServiceMode.PREPARATION,
            materialRevision = 1,
            deadlineEpochMs = now + 10 * 60_000,
            triggerKeys = setOf("surface:door"),
        )
        val image = ImageQuality(true, 100.0, 80.0, 0.3, 1280, 720, 0, "sha")
        val result = AnchorScheduler(AnchorPolicy(), idFactory = { "1" }).evaluate(
            baseSnapshot(now, episode, image), "obs", "asset"
        )
        assertEquals(1, result.emitted.size)
        assertTrue(result.emitted.single().score >= 0.45, "score below threshold")
    }

    test("speech and display block checkpoint") {
        val now = 1_800_000_000_000L
        val episode = CandidateEpisode("ep", "obj", "intent", ServiceMode.AWARENESS)
        val image = ImageQuality(true, 100.0, 80.0, 0.3, 1280, 720, 0, "sha")
        val base = baseSnapshot(now, episode, image)
        val result = AnchorScheduler(AnchorPolicy()).evaluate(
            base.copy(interaction = InteractionSnapshot(speechRatio = 0.8, serviceCardVisible = true)),
            "obs", "asset"
        )
        assertTrue(AnchorBlocker.SPEECH_ACTIVE in result.blockers, "speech blocker missing")
        assertTrue(AnchorBlocker.DISPLAY_BUSY in result.blockers, "display blocker missing")
        assertTrue(result.emitted.isEmpty(), "blocked checkpoint emitted")
    }

    fun quietSnapshot(episodes: List<CandidateEpisode>,elapsed: Long=100_000L): SchedulerSnapshot {
        val image=ImageQuality(true,100.0,80.0,0.0,1280,720,0,"hash")
        return baseSnapshot(1_800_000_000_000L+elapsed,episodes.first(),image).copy(nowElapsedMs=elapsed,
            candidateEpisodes=episodes,lastMotionSeenElapsedMs=null,lastMaterialChangeEpochMs=null,materialChangePending=false)
    }
    test("periodic coverage preserves unchanged negative candidates and respects episode cooldown") {
        val scheduler=AnchorScheduler(AnchorPolicy())
        val closed=CandidateEpisode("closed","obj","intent",ServiceMode.AWARENESS,lifecycle=EpisodeLifecycle.COMPLETED)
        val snapshot=quietSnapshot(listOf(closed))
        val first=scheduler.evaluate(snapshot,"obs1","image1")
        assertEquals(1,first.emitted.size)
        assertTrue(AnchorTriggerFamily.PERIODIC_COVERAGE in first.emitted.single().triggerFamilies,"stable dwell swallowed periodic coverage")
        assertTrue(first.emitted.single().score<0.45,"coverage must preserve the actual soft score")
        val tooSoon=scheduler.evaluate(snapshot.copy(nowElapsedMs=160_000,lastEvaluationElapsedMs=100_000),"obs2","image2")
        assertTrue(tooSoon.emitted.isEmpty(),"same episode bypassed its 90-second cooldown")
        assertTrue(AnchorBlocker.EPISODE_COOLDOWN in tooSoon.blockers,"missing episode cooldown diagnostic")
        assertEquals(1,scheduler.evaluate(snapshot.copy(nowElapsedMs=190_000,lastEvaluationElapsedMs=100_000),"obs3","image3").emitted.size)
    }
    test("material revision bypass is scoped to the changed candidate") {
        val scheduler=AnchorScheduler(AnchorPolicy())
        val a=CandidateEpisode("a","oa","ia",ServiceMode.PREPARATION)
        val b=CandidateEpisode("b","ob","ib",ServiceMode.PREPARATION)
        scheduler.evaluate(quietSnapshot(listOf(a,b)),"first","image1")
        val changed=quietSnapshot(listOf(a.copy(materialRevision=1),b),110_000).copy(lastEvaluationElapsedMs=100_000,
            materialChangePending=true,materialChangedEpisodeHandles=setOf("a"))
        val result=scheduler.evaluate(changed,"change","image2")
        assertEquals(listOf("a"),result.emitted.map {it.candidateEpisodeHandle})
        assertTrue(AnchorBlocker.EPISODE_COOLDOWN in result.blockersByEpisode.getValue("b"),"unrelated candidate bypassed cooldown")
    }
    test("routine window has a ten-minute budget while directed service audit can run earlier") {
        val routine=CandidateEpisode("routine","obj","intent",ServiceMode.AWARENESS,routineWindowActive=true)
        val scheduler=AnchorScheduler(AnchorPolicy())
        scheduler.evaluate(quietSnapshot(listOf(routine)),"first","image1")
        val early=scheduler.evaluate(quietSnapshot(listOf(routine),190_000),"early","image2")
        assertTrue(early.emitted.isEmpty(),"routine repeated before ten minutes")
        assertTrue(AnchorBlocker.ROUTINE_COOLDOWN in early.blockers,"routine cooldown diagnostic missing")
        assertEquals(1,scheduler.evaluate(quietSnapshot(listOf(routine),700_000),"due","image3").emitted.size)
        val audit=scheduler.evaluate(quietSnapshot(listOf(routine),730_000).copy(lastEvaluationElapsedMs=700_000),"audit","image4",setOf("routine"))
        assertEquals(1,audit.emitted.size)
        assertTrue(AnchorTriggerFamily.POST_SERVICE_AUDIT in audit.emitted.single().triggerFamilies,"directed audit was lost")
    }
    test("coverage never bypasses display gate and capped candidates remain eligible") {
        val episodes=(1..5).map {CandidateEpisode("ep$it","obj$it","intent$it",ServiceMode.AWARENESS)}
        val scheduler=AnchorScheduler(AnchorPolicy())
        val snapshot=quietSnapshot(episodes)
        assertTrue(scheduler.evaluate(snapshot.copy(interaction=InteractionSnapshot(serviceCardVisible=true)),"blocked","image1").emitted.isEmpty(),"busy display accepted coverage")
        val first=scheduler.evaluate(snapshot,"first","image2")
        val second=scheduler.evaluate(snapshot.copy(nowElapsedMs=130_000,lastEvaluationElapsedMs=100_000),"second","image3")
        assertEquals(3,first.emitted.size);assertEquals(2,second.emitted.size)
        assertEquals(5,(first.emitted+second.emitted).map {it.candidateEpisodeHandle}.toSet().size)
        scheduler.reset()
        assertEquals(3,scheduler.evaluate(snapshot,"new-session","image4").emitted.size)
    }
    test("deployment profile retains the configured soft-score threshold") {
        val episode=CandidateEpisode("ep","obj","intent",ServiceMode.AWARENESS)
        val result=AnchorScheduler(AnchorPolicy(mode=SchedulerMode.DEPLOYMENT_OPTIMIZED)).evaluate(quietSnapshot(listOf(episode)),"obs","image")
        assertTrue(result.emitted.isEmpty(),"deployment unexpectedly used the research coverage override")
    }

    test("future isolation uses availableAt") {
        val store = CausalContextStore()
        store.append(ObservationRecord("past", ObservationKind.SYNTHETIC_CONTEXT, 100, 110, "past"))
        store.append(ObservationRecord("late", ObservationKind.SYNTHETIC_CONTEXT, 100, 210, "late"))
        assertEquals(setOf("past"), store.visibleIds(150))
    }

    test("inference input excludes scheduler diagnostics") {
        val now = 1_800_000_000_000L
        val anchor = AnchorCandidate(
            "a", now, "obs", "asset", "obj", "ep",
            setOf(AnchorTriggerFamily.TRANSITION_SETTLED), 0.8,
            mapOf("private" to "must-not-leak"),
        )
        val request = InferenceContract.buildRequest(
            "r", anchor,
            ImageQuality(true, 100.0, 80.0, 0.4, 1280, 720, 0, "sha"),
            listOf(ObservationRecord("obs1", ObservationKind.SYNTHETIC_CONTEXT, now - 10, now - 10, "visible")),
            emptyList(), "p", "x"
        )
        val rendered = request.toString()
        assertFalse("must-not-leak" in rendered, "scheduler diagnostics leaked")
    }

    test("NO_SERVICE cannot carry HOW") {
        val request = InferenceRequest(
            requestId = "r", anchorId = "a", anchorTimeEpochMs = 1,
            currentAssetId = "asset", currentImageSha256 = "sha",
            candidate = ModelCandidate("obj", "ep"), observations = emptyList(), receipts = emptyList(),
            promptHash = "p", preprocessHash = "x",
        )
        val response = InferenceResponse(
            requestId = "r", decisionId = "d", decision = DecisionLabel.NO_SERVICE,
            title = "bad", producedAtEpochMs = 1, expiresAtEpochMs = 2,
        )
        assertTrue(InferenceContract.validate(response, request).isNotEmpty(), "invalid HOW accepted")
    }

    test("receipt only after visible ack and material change reactivates") {
        val now = 1_800_000_000_000L
        val episode = CandidateEpisode("ep", "obj", "intent", ServiceMode.PREPARATION, materialRevision = 1)
        val decision = ServiceDecision(
            "r", "d", DecisionLabel.SERVICE, ServiceMode.PREPARATION,
            "title", "body", setOf("e"), now, now + 20_000,
        )
        val policy = ReceiptPolicy(receiptIdFactory = { "1" })
        val failed = policy.finalizeDelivery(decision, episode, "obj", null, setOf("e"), now + 1_000)
        assertEquals(DeliveryStatus.DISPLAY_FAILED, failed.status)
        assertTrue(policy.allReceipts().isEmpty(), "receipt committed without visible ack")
        val visible = policy.finalizeDelivery(decision, episode, "obj", now + 2_000, setOf("e"), now + 2_000)
        assertEquals(DeliveryStatus.DISPLAY_VISIBLE, visible.status)
        assertTrue(policy.shouldSuppress(episode), "same revision not suppressed")
        assertFalse(policy.shouldSuppress(episode.copy(materialRevision = 2)), "material change did not reactivate")
    }

    test("state machine preserves decision correlation") {
        val machine = StudyStateMachine()
        machine.dispatch(StudyStateMachine.Event.Start("s", 1))
        machine.dispatch(StudyStateMachine.Event.AnchorReady("a", "r"))
        machine.dispatch(StudyStateMachine.Event.DecisionReady("d", true))
        machine.dispatch(StudyStateMachine.Event.DisplayVisible("d"))
        assertTrue(machine.state is StudyStateMachine.State.ServiceVisible, "service not visible")
    }


    test("trigger index routes candidates without deciding service") {
        val a = CandidateEpisode("ep-a", "obj-a", "intent-a", ServiceMode.AWARENESS, triggerKeys = setOf("surface:door"))
        val b = CandidateEpisode("ep-b", "obj-b", "intent-b", ServiceMode.DECISION, triggerKeys = setOf("surface:checkout"))
        val index = ai.opstate.core.episode.TriggerIndex()
        index.rebuild(listOf(a, b))
        assertEquals(listOf("ep-a"), index.query(setOf("surface:door"), false).map { it.episodeHandle })
        assertEquals(2, index.query(emptySet(), true).size)
    }

    test("audit chain detects tampering") {
        val chain = ai.opstate.core.runtime.AuditChain()
        chain.append("OBSERVATION", 1, "a".encodeToByteArray())
        chain.append("ANCHOR", 2, "b".encodeToByteArray())
        assertTrue(chain.verify(), "valid audit chain rejected")
        val tampered = chain.all().toMutableList()
        tampered[1] = tampered[1].copy(payloadSha256 = "f".repeat(64))
        assertFalse(chain.verify(tampered), "tampered audit chain accepted")
    }

    test("request builder filters future observations and receipts itself") {
        val anchor=AnchorCandidate("a",100,"image","asset","obj","ep",emptySet(),0.5,emptyMap())
        val image=ImageQuality(true,100.0,80.0,0.2,1280,720,0,"hash")
        val receipt=ActionReceipt("receipt","decision","ep","obj","intent",ServiceMode.PREPARATION,1,101,emptySet())
        val request=InferenceContract.buildRequest("r",anchor,image,listOf(
            ObservationRecord("past",ObservationKind.SYNTHETIC_CONTEXT,80,90,"past"),
            ObservationRecord("future",ObservationKind.SYNTHETIC_CONTEXT,80,101,"late"),
        ),listOf(receipt),"p","x")
        assertEquals(listOf("past"),request.observations.map { it.observationId })
        assertTrue(request.receipts.isEmpty(),"future receipt entered the request")
    }

    test("visible ACK at expiry or from before the decision cannot create receipt") {
        val episode=CandidateEpisode("ep","obj","intent",ServiceMode.PREPARATION)
        val decision=ServiceDecision("r","d",DecisionLabel.SERVICE,ServiceMode.PREPARATION,"title","body",emptySet(),100,200)
        val policy=ReceiptPolicy()
        assertEquals(DeliveryStatus.STALE_DECISION,policy.finalizeDelivery(decision,episode,"obj",200,emptySet(),200).status)
        assertEquals(DeliveryStatus.DISPLAY_FAILED,policy.finalizeDelivery(decision,episode,"obj",90,emptySet(),150).status)
        assertTrue(policy.allReceipts().isEmpty(),"invalid ACK committed a receipt")
    }

    test("receipt storage failure cannot suppress the next valid delivery") {
        val episode=CandidateEpisode("ep","obj","intent",ServiceMode.PREPARATION)
        val decision=ServiceDecision("r","d",DecisionLabel.SERVICE,ServiceMode.PREPARATION,"title","body",setOf("obs"),100,500)
        val policy=ReceiptPolicy()
        val failed=runCatching {policy.finalizeDelivery(decision,episode,"obj",200,setOf("obs"),200) {error("DISK_UNAVAILABLE")}}
        assertTrue(failed.isFailure,"persistence failure was hidden")
        assertTrue(policy.allReceipts().isEmpty(),"unpersisted receipt entered causal history")
        assertFalse(policy.shouldSuppress(episode),"unpersisted receipt suppressed future service")
        var writes=0
        val committed=policy.finalizeDelivery(decision,episode,"obj",200,setOf("obs"),210) {writes++}
        assertEquals(DeliveryStatus.DISPLAY_VISIBLE,committed.status)
        assertTrue(policy.shouldSuppress(episode),"persisted receipt did not suppress duplicate")
        policy.finalizeDelivery(decision,episode,"obj",200,setOf("obs"),220) {writes++}
        assertEquals(1,writes,"duplicate visible ACK was persisted twice")
    }

    test("image measurements distinguish darkness clipping blur and a valid detailed image") {
        val black=ImageQualityStatistics.analyze(IntArray(32*24) {0xff000000.toInt()},32,24)
        assertEquals(ImageQualityStatus.TOO_DARK,black.status);assertEquals(1.0,black.darkPixelRatio);assertEquals(0.0,black.brightPixelRatio)
        val white=ImageQualityStatistics.analyze(IntArray(32*24) {0xffffffff.toInt()},32,24)
        assertEquals(ImageQualityStatus.TOO_BRIGHT,white.status);assertEquals(1.0,white.brightPixelRatio)
        val plain=ImageQualityStatistics.analyze(IntArray(32*24) {0xff808080.toInt()},32,24)
        assertEquals(ImageQualityStatus.BLURRY,plain.status);assertEquals(0.0,plain.darkPixelRatio);assertEquals(0.0,plain.brightPixelRatio)
        val detail=ImageQualityStatistics.analyze(IntArray(32*24) {if((it/32+it%32)%2==0) 0xff000000.toInt() else 0xffffffff.toInt()},32,24)
        assertEquals(ImageQualityStatus.VALID,detail.status);assertEquals(.5,detail.darkPixelRatio);assertEquals(.5,detail.brightPixelRatio)
        assertTrue(detail.blurScore>ImageQualityStatistics.MIN_EDGE_ENERGY,"detailed sample classified blurry")
    }
    test("image metrics reject mismatched dimensions") {
        assertTrue(runCatching {ImageQualityStatistics.analyze(IntArray(20),32,24)}.isFailure,"bad pixel buffer accepted")
    }
    test("streaming front end emits causal HR packets without prescribed anchors") {
        val dark=VisualSignature(0, List(48) {0.0})
        val changed=VisualSignature(-1, List(48) {if(it%16==15) 1.0 else 0.0})
        val frontend=StreamingPacketizer()
        val first=frontend.onImage("obs-1","asset-1",1_000,dark).single()
        assertEquals(setOf("VISUAL"),first.triggerTypes)
        assertEquals(listOf("asset-1"),first.imageIds)
        assertTrue(frontend.onDigital("event-1",2_000).isEmpty(),"digital event was not debounced")
        val digital=frontend.advance(17_000).single()
        assertEquals(setOf("DIGITAL"),digital.triggerTypes)
        assertEquals(listOf("event-1"),digital.newEventIds)
        assertEquals(listOf("asset-1"),digital.reusedImageIds)
        assertTrue(frontend.onImage("obs-2","asset-2",100_000,changed).isEmpty(),"visual cooldown was bypassed")
        val visual=frontend.onImage("obs-3","asset-3",181_000,changed).single()
        assertEquals(listOf("obs-3"),visual.newEventIds)
        assertEquals(listOf("asset-3"),visual.imageIds)
    }
    test("history retrieval packet context is causal recent and lexically prefetched") {
        val store=CausalContextStore()
        store.append(ObservationRecord("old-match",ObservationKind.SYNTHETIC_CONTEXT,1,1,"bring the blue passport folder"))
        store.append(ObservationRecord("old-noise",ObservationKind.SYNTHETIC_CONTEXT,2,2,"unrelated weather note"))
        store.append(ObservationRecord("recent",ObservationKind.SYNTHETIC_CONTEXT,7_100_000,7_100_000,"passport preparation changed"))
        repeat(6) {index->store.append(ObservationRecord("history-image-$index",ObservationKind.IMAGE,7_110_000L+index,7_110_000L+index,
            "Collected historical image.",assetId="history-asset-$index"))}
        store.append(ObservationRecord("new",ObservationKind.SYNTHETIC_CONTEXT,7_200_000,7_200_000,"check passport now"))
        store.append(ObservationRecord("future",ObservationKind.SYNTHETIC_CONTEXT,7_300_000,7_300_000,"passport from the future"))
        val rows=store.historyRetrievalSnapshot(7_200_000,listOf("new"),recentWindowMs=100_000)
        assertTrue(rows.map {it.observationId}.containsAll(listOf("new","recent","old-match")),"HR working set missed relevant context")
        assertEquals(listOf("history-image-2","history-image-3","history-image-4","history-image-5"),rows.filter {it.kind==ObservationKind.IMAGE}.map {it.observationId})
        assertFalse(rows.any {it.observationId in setOf("old-noise","future")},"HR working set leaked noise or future evidence")
    }
    println("ALL TESTS PASSED: $passed")
}
