package ai.opstate.rokid.phone.runtime

import ai.opstate.core.context.CausalContextStore
import ai.opstate.core.hr.*
import ai.opstate.core.inference.*
import ai.opstate.core.model.*
import ai.opstate.protocol.StudyWire
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class HrTurnRunnerTest {
    private val now=50_000_000L
    private val anchor=now-1_000L
    private fun row(id: String,kind: ObservationKind,at: Long,text: String,asset: String?=null)=
        ObservationRecord(id,kind,at,at,text,assetId=asset)
    private fun request()=InferenceRequest("opstate-online-hr-mobile-v1","request-1","packet-1",anchor,
        "current-asset",StudyWire.sha256("photo".toByteArray()),ModelCandidate("stream","episode"),
        listOf(ModelObservation("current",ObservationKind.IMAGE,anchor,anchor,"frame","current-asset",null,null)),
        emptyList(),"prompt","preprocess","fixture","parser",setOf("VISUAL","MANUAL"),listOf("current","task"),0,false)

    @Test fun missingDateRetrievesOnlyCausalHistoryAndFinalCitesBothSides()=runBlocking {
        val history=CausalContextStore()
        history.append(row("date",ObservationKind.SYNTHETIC_CONTEXT,now-7*60*60_000L,
            "周五十八点学生中心三楼签到"))
        history.append(row("late-exposure",ObservationKind.SYNTHETIC_CONTEXT,now-8*60*60_000L,
            "周五十八点学生中心三楼已取消"),exposedAtEpochMs=now+1)
        history.append(row("task",ObservationKind.SYNTHETIC_CONTEXT,anchor,"C-311 状态未确认"))
        history.append(row("current",ObservationKind.IMAGE,anchor,"frame","current-asset"))
        var calls=0
        val provider=object: HrStageProvider {
            override suspend fun step(envelope: JsonObject): HrStep {
                calls++
                val visible=envelope.getValue("visible_reference_ids").jsonArray.map {it.jsonPrimitive.content}
                assertTrue("task" in visible);assertFalse("date" in visible);assertFalse("late-exposure" in visible)
                return HrStep(HrVisualNote("",emptyList()),HrCandidate(HrCandidateKind.OPEN_ITEM,"需要截止时间",
                    listOf("task"),listOf("签到日期"),HrRoute.RETRIEVE),HrRoute.RETRIEVE,"学生中心三楼 周五 十八点",null)
            }
            override suspend fun final(envelope: JsonObject): HrFinal {
                calls++
                val visible=envelope.getValue("visible_reference_ids").jsonArray.map {it.jsonPrimitive.content}
                assertTrue("date" in visible);assertTrue("task" in visible);assertFalse("late-exposure" in visible)
                return HrFinal(HrRoute.SERVICE,HrService("PREPARATION","签到时间","C-311 周五 18:00 签到，状态仍待确认。",
                    listOf("date","task")))
            }
        }
        val outcome=HrTurnRunner(history,provider,{now}).run(request(),emptyList(),20_000)
        assertEquals(2,calls)
        assertEquals(DecisionLabel.SERVICE,outcome.response.decision)
        assertEquals(setOf("date","task"),outcome.response.evidenceObservationIds)
        assertTrue(outcome.request.observations.any {it.observationId=="date"})
        assertFalse(outcome.request.observations.any {it.observationId=="late-exposure"})
    }

    @Test fun secondRetrievalAndMissingEvidenceAreRejected()=runBlocking {
        val history=CausalContextStore()
        history.append(row("task",ObservationKind.SYNTHETIC_CONTEXT,anchor,"C-311 未确认"))
        history.append(row("current",ObservationKind.IMAGE,anchor,"frame","current-asset"))
        val step=HrStep(HrVisualNote("",emptyList()),HrCandidate(HrCandidateKind.OPEN_ITEM,"查时间",
            listOf("task"),listOf("日期"),HrRoute.RETRIEVE),HrRoute.RETRIEVE,"C-311 日期",null)
        val provider=object: HrStageProvider {
            override suspend fun step(envelope: JsonObject)=step
            override suspend fun final(envelope: JsonObject)=HrFinal(HrRoute.RETRIEVE,null)
        }
        assertEquals("HR_SECOND_RETRIEVAL_FORBIDDEN",
            runCatching {HrTurnRunner(history,provider,{now}).run(request(),emptyList(),20_000)}.exceptionOrNull()?.message)
        val invalid=object: HrStageProvider {
            override suspend fun step(envelope: JsonObject)=step.copy(route=HrRoute.SERVICE,
                candidate=step.candidate.copy(nextAction=HrRoute.SERVICE,missingFacts=emptyList()),query=null,
                service=HrService("DECISION","错误","不存在的证据",listOf("future")))
            override suspend fun final(envelope: JsonObject)=error("must not call FINAL")
        }
        assertEquals("HR_SERVICE_EVIDENCE_INVALID",
            runCatching {HrTurnRunner(history,invalid,{now}).run(request(),emptyList(),20_000)}.exceptionOrNull()?.message)
    }

    @Test fun stalePacketNeverCallsProvider()=runBlocking {
        var called=false
        val provider=object: HrStageProvider {
            override suspend fun step(envelope: JsonObject): HrStep {called=true;error("unexpected")}
            override suspend fun final(envelope: JsonObject): HrFinal {called=true;error("unexpected")}
        }
        assertEquals("HR_PACKET_STALE",runCatching {
            HrTurnRunner(CausalContextStore(),provider,{anchor+30_000}).run(request(),emptyList(),20_000)
        }.exceptionOrNull()?.message)
        assertFalse(called)
    }

    @Test fun nativeSearchPreservesEntityAndNegatedStateWithoutFutureResults() {
        val history=CausalContextStore()
        history.append(row("open",ObservationKind.SYNTHETIC_CONTEXT,100,"C-311 申请状态未确认"))
        history.append(row("closed",ObservationKind.SYNTHETIC_CONTEXT,101,"C-312 申请状态已完成"))
        history.append(row("not-exposed",ObservationKind.SYNTHETIC_CONTEXT,102,"C-311 申请状态已完成"),103)
        assertEquals(listOf("open"),history.searchArchive("C-311 未确认",102,102).map {it.observationId})
        assertEquals(emptyList<String>(),history.searchArchive("C-311 未确认",99,102).map {it.observationId})
    }

    @Test fun identicalJpegAssetsStillUseTheNewPacketObservation()=runBlocking {
        val history=CausalContextStore()
        history.append(row("older",ObservationKind.IMAGE,anchor-1_000,"same pixels","current-asset"))
        history.append(row("newer",ObservationKind.IMAGE,anchor,"same pixels","current-asset"))
        val request=request().copy(packetNewEventIds=listOf("newer"),observations=listOf(
            ModelObservation("older",ObservationKind.IMAGE,anchor-1_000,anchor-1_000,"same pixels","current-asset",null,null),
            ModelObservation("newer",ObservationKind.IMAGE,anchor,anchor,"same pixels","current-asset",null,null)))
        val provider=object: HrStageProvider {
            override suspend fun step(envelope: JsonObject): HrStep {
                val image=envelope.getValue("image_manifest").jsonArray.single().jsonObject.getValue("image_id").jsonPrimitive.content
                assertEquals("newer",image)
                return HrStep(HrVisualNote("",emptyList()),HrCandidate(HrCandidateKind.OPEN_ITEM,"现场核对",
                    listOf(image),emptyList(),HrRoute.SERVICE),HrRoute.SERVICE,null,
                    HrService("AWARENESS","眼前状态","当前画面需要核对。",listOf(image)))
            }
            override suspend fun final(envelope: JsonObject): HrFinal=error("unexpected")
        }
        val outcome=HrTurnRunner(history,provider,{now}).run(request,emptyList(),20_000)
        assertEquals(setOf("newer"),outcome.response.evidenceObservationIds)
    }

    @Test fun aDigitalPacketCanDecideWithoutAnyCameraImage()=runBlocking {
        val history=CausalContextStore()
        history.append(row("deadline",ObservationKind.SYNTHETIC_CONTEXT,anchor,"今天 16:00 前交接材料"))
        val request=request().copy(currentAssetId="NO_IMAGE",currentImageSha256=StudyWire.sha256(ByteArray(0)),
            packetTriggerTypes=setOf("DIGITAL"),packetNewEventIds=listOf("deadline"),packetObservationGap=true,
            observations=listOf(ModelObservation("deadline",ObservationKind.SYNTHETIC_CONTEXT,anchor,anchor,
                "今天 16:00 前交接材料",null,null,null)))
        val provider=object: HrStageProvider {
            override suspend fun step(envelope: JsonObject): HrStep {
                assertTrue(envelope.getValue("image_manifest").jsonArray.isEmpty())
                assertTrue(envelope.getValue("visible_reference_ids").jsonArray.any {it.jsonPrimitive.content=="deadline"})
                return HrStep(HrVisualNote("",emptyList()),HrCandidate(HrCandidateKind.HANDOFF,"新的交接记录已到",
                    listOf("deadline"),emptyList(),HrRoute.SERVICE),HrRoute.SERVICE,null,
                    HrService("PREPARATION","交接提醒","16:00 前交接材料。",listOf("deadline")))
            }
            override suspend fun final(envelope: JsonObject): HrFinal=error("unexpected")
        }
        val outcome=HrTurnRunner(history,provider,{now}).run(request,emptyList(),20_000)
        assertEquals(DecisionLabel.SERVICE,outcome.response.decision)
        assertEquals(setOf("deadline"),outcome.response.evidenceObservationIds)
    }

    @Test fun aSpecificDocumentInTheImageCanRetrieveAnOlderTaskNotPrefetched()=runBlocking {
        val history=CausalContextStore()
        history.append(row("older-task",ObservationKind.SYNTHETIC_CONTEXT,now-7*60*60_000L,
            "数据看板改版方案 9月25日16:00评审，需异常展示章节"))
        history.append(row("current",ObservationKind.IMAGE,anchor,"document image","current-asset"))
        val provider=object: HrStageProvider {
            override suspend fun step(envelope: JsonObject): HrStep {
                val visible=envelope.getValue("visible_reference_ids").jsonArray.map {it.jsonPrimitive.content}
                assertFalse("older-task" in visible)
                return HrStep(HrVisualNote("文档显示数据异常监测章节",listOf("current")),
                    HrCandidate(HrCandidateKind.OPEN_ITEM,"画面出现具体任务文档",listOf("current"),
                        listOf("旧任务要求与截止时间"),HrRoute.RETRIEVE),HrRoute.RETRIEVE,
                    "数据看板 异常展示 评审",null)
            }
            override suspend fun final(envelope: JsonObject): HrFinal {
                val visible=envelope.getValue("visible_reference_ids").jsonArray.map {it.jsonPrimitive.content}
                assertTrue("older-task" in visible)
                return HrFinal(HrRoute.SERVICE,HrService("PREPARATION","方案核对",
                    "9月25日评审前，请核对异常展示章节。",listOf("older-task")))
            }
        }
        val outcome=HrTurnRunner(history,provider,{now}).run(request().copy(
            packetNewEventIds=listOf("current"),packetTriggerTypes=setOf("VISUAL")),emptyList(),20_000)
        assertEquals(DecisionLabel.SERVICE,outcome.response.decision)
        assertEquals(setOf("older-task"),outcome.response.evidenceObservationIds)
        assertTrue("older-task" in outcome.retrievedIds)
    }

    @Test fun guaranteedEffortAsksTheModelForACardAfterAutomaticOrManualSilence()=runBlocking {
        for(triggers in listOf(setOf("VISUAL"),setOf("MANUAL"))) {
            val history=CausalContextStore().apply {append(row("current",ObservationKind.IMAGE,
                anchor,"recorded frame","current-asset"))}
            val input=request().copy(packetNewEventIds=listOf("current"),packetTriggerTypes=triggers)
            val provider=object: HrStageProvider {
                override suspend fun step(envelope: JsonObject)=HrStep(HrVisualNote("",emptyList()),
                    HrCandidate(null,null,emptyList(),emptyList(),HrRoute.SILENT),HrRoute.SILENT,null,null)
                override suspend fun final(envelope: JsonObject): HrFinal {
                    assertEquals("GUARANTEED_SERVICE",envelope.getValue("stage").jsonPrimitive.content)
                    return HrFinal(HrRoute.SERVICE,HrService("AWARENESS","当前设备状态",
                        "画面显示一台测试设备，先核对屏幕当前状态。",listOf("current")))
                }
            }
            val restrained=HrTurnRunner(history,provider,{now},effort=ProactivityEffort.RESTRAINED)
                .run(input,emptyList(),20_000)
            assertEquals(DecisionLabel.NO_SERVICE,restrained.response.decision)
            assertFalse(restrained.forcedModelPass)
            val inclined=HrTurnRunner(history,provider,{now},effort=ProactivityEffort.INCLINED)
                .run(input,emptyList(),20_000)
            assertEquals(DecisionLabel.NO_SERVICE,inclined.response.decision)
            val guaranteed=HrTurnRunner(history,provider,{now},effort=ProactivityEffort.GUARANTEED)
                .run(input,emptyList(),20_000)
            assertEquals(DecisionLabel.SERVICE,guaranteed.response.decision)
            assertEquals(setOf("current"),guaranteed.response.evidenceObservationIds)
            assertTrue(guaranteed.forcedModelPass)
            assertEquals("画面显示一台测试设备，先核对屏幕当前状态。",guaranteed.response.body)
        }
    }

    @Test fun liveManualAndCvttPhotosAreFreshEvenWithoutTheLegacyVisualTrigger()=runBlocking {
        for(trigger in listOf("MANUAL","CVTT_COLD_START")) {
            val history=CausalContextStore().apply {append(row("current",ObservationKind.IMAGE,
                anchor,"new live photo","current-asset"))}
            val input=request().copy(packetNewEventIds=listOf("current"),packetTriggerTypes=setOf(trigger))
            val provider=object: HrStageProvider {
                override suspend fun step(envelope: JsonObject): HrStep {
                    assertEquals(false,envelope.getValue("image_manifest").jsonArray.single().jsonObject
                        .getValue("reused").jsonPrimitive.boolean)
                    return HrStep(HrVisualNote("当前画面显示桌面",listOf("current")),
                        HrCandidate(null,null,emptyList(),emptyList(),HrRoute.SILENT),HrRoute.SILENT,null,null)
                }
                override suspend fun final(envelope: JsonObject): HrFinal {
                    assertEquals("GUARANTEED_SERVICE",envelope.getValue("stage").jsonPrimitive.content)
                    return HrFinal(HrRoute.SERVICE,HrService("AWARENESS","画面核对",
                        "画面显示桌面，请核对当前可见状态。",listOf("current")))
                }
            }
            val result=HrTurnRunner(history,provider,{now},effort=ProactivityEffort.GUARANTEED)
                .run(input,emptyList(),20_000)
            assertEquals(DecisionLabel.SERVICE,result.response.decision)
            assertTrue(result.forcedModelPass)
        }
    }

    @Test fun guaranteedModeNeverSynthesizesAProgramCardWhenTheModelStillRefuses()=runBlocking {
        val history=CausalContextStore().apply {append(row("current",ObservationKind.IMAGE,
            anchor,"new live photo","current-asset"))}
        val provider=object: HrStageProvider {
            override suspend fun step(envelope: JsonObject)=HrStep(HrVisualNote("",emptyList()),
                HrCandidate(null,null,emptyList(),emptyList(),HrRoute.SILENT),HrRoute.SILENT,null,null)
            override suspend fun final(envelope: JsonObject)=HrFinal(HrRoute.SILENT,null)
        }
        val error=runCatching {HrTurnRunner(history,provider,{now},effort=ProactivityEffort.GUARANTEED)
            .run(request().copy(packetTriggerTypes=setOf("MANUAL"),packetNewEventIds=listOf("current")),emptyList(),20_000)}
            .exceptionOrNull()
        assertEquals("HR_GUARANTEED_MODEL_SILENT",error?.message)
    }

    @Test fun guaranteedDirectModelServiceDoesNotCallTheSecondStage()=runBlocking {
        val history=CausalContextStore().apply {append(row("current",ObservationKind.IMAGE,
            anchor,"new live photo","current-asset"))}
        val provider=object: HrStageProvider {
            override suspend fun step(envelope: JsonObject)=HrStep(HrVisualNote("",emptyList()),
                HrCandidate(HrCandidateKind.OPEN_ITEM,"当前图像可核对",listOf("current"),emptyList(),HrRoute.SERVICE),
                HrRoute.SERVICE,null,HrService("AWARENESS","画面细节","画面里有一台测试设备。",listOf("current")))
            override suspend fun final(envelope: JsonObject): HrFinal=error("second model stage must not run")
        }
        val result=HrTurnRunner(history,provider,{now},effort=ProactivityEffort.GUARANTEED)
            .run(request().copy(packetTriggerTypes=setOf("MANUAL"),packetNewEventIds=listOf("current")),emptyList(),20_000)
        assertEquals("画面里有一台测试设备。",result.response.body)
        assertFalse(result.forcedModelPass)
    }

    @Test fun guaranteedModeRejectsTheOldStockTextAndRequestsModelRewrite()=runBlocking {
        val history=CausalContextStore().apply {append(row("current",ObservationKind.IMAGE,
            anchor,"new live photo","current-asset"))}
        val provider=object: HrStageProvider {
            override suspend fun step(envelope: JsonObject)=HrStep(HrVisualNote("",emptyList()),
                HrCandidate(HrCandidateKind.OPEN_ITEM,"有新图像",listOf("current"),emptyList(),HrRoute.SERVICE),
                HrRoute.SERVICE,null,HrService("AWARENESS","现场观察",
                    "这张画面已纳入本次观察，可在手机记录中查看。",listOf("current")))
            override suspend fun final(envelope: JsonObject): HrFinal {
                assertEquals("HR_TEMPLATE_CONTENT",envelope.getValue("regeneration_reason").jsonPrimitive.content)
                return HrFinal(HrRoute.SERVICE,HrService("AWARENESS","屏幕状态核对",
                    "画面显示测试设备的屏幕仍亮着，请核对当前显示内容。",listOf("current")))
            }
        }
        val result=HrTurnRunner(history,provider,{now},effort=ProactivityEffort.GUARANTEED)
            .run(request().copy(packetNewEventIds=listOf("current")),emptyList(),20_000)
        assertTrue(result.forcedModelPass)
        assertEquals("屏幕状态核对",result.response.title)
        assertEquals("HR_TEMPLATE_CONTENT",result.timingSuppression)
    }

    @Test fun olderImageCarriedByDigitalPacketRemainsReused()=runBlocking {
        val history=CausalContextStore().apply {
            append(row("current",ObservationKind.IMAGE,anchor-500,"older photo","current-asset"))
            append(row("new-note",ObservationKind.SYNTHETIC_CONTEXT,anchor,"new digital event"))
        }
        val input=request().copy(packetNewEventIds=listOf("new-note"),packetTriggerTypes=setOf("DIGITAL"))
        val provider=object: HrStageProvider {
            override suspend fun step(envelope: JsonObject): HrStep {
                assertEquals(true,envelope.getValue("image_manifest").jsonArray.single().jsonObject
                    .getValue("reused").jsonPrimitive.boolean)
                return HrStep(HrVisualNote("",emptyList()),
                    HrCandidate(null,null,emptyList(),emptyList(),HrRoute.SILENT),HrRoute.SILENT,null,null)
            }
            override suspend fun final(envelope: JsonObject): HrFinal=error("unexpected")
        }
        assertEquals(DecisionLabel.NO_SERVICE,HrTurnRunner(history,provider,{now})
            .run(input,emptyList(),20_000).response.decision)
    }

    @Test fun deletingAVisualSourceAlsoWithdrawsItsDerivedNotesFromTheWorkingSet() {
        val store=CausalContextStore()
        store.append(row("photo",ObservationKind.IMAGE,anchor,"original","asset-photo"))
        store.append(row("note",ObservationKind.MODEL_NOTE,anchor,"看见桌上的预约单"),
            sourceIds=setOf("photo"))
        store.append(row("derived",ObservationKind.MODEL_NOTE,anchor,"预约单摘要"),
            sourceIds=setOf("note"))
        store.append(row("unrelated",ObservationKind.SYNTHETIC_CONTEXT,anchor,"另一个真实记录"))
        assertEquals("photo",store.remove("photo")?.observationId)
        assertEquals(listOf("unrelated"),store.causalArchiveSnapshot(now,now).map {it.observationId})
    }
}
