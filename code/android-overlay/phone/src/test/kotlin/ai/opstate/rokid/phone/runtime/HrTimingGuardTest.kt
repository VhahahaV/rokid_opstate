package ai.opstate.rokid.phone.runtime

import ai.opstate.core.hr.HrService
import ai.opstate.core.hr.HrTimingGuard
import ai.opstate.core.hr.HrVisualNote
import ai.opstate.core.model.ObservationKind
import ai.opstate.core.model.ObservationRecord
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class HrTimingGuardTest {
    private fun at(text: String)=ZonedDateTime.parse(text).toInstant().toEpochMilli()
    private fun record(text: String)=ObservationRecord("source",ObservationKind.SYNTHETIC_CONTEXT,0,0,text)
    private fun card(body: String)=HrService("PREPARATION","截止提醒",body,listOf("source"))

    @Test fun existingExperimentFutureDeadlinesCannotBeCalledTonightOrMinutesAway() {
        val day1=at("2026-09-21T12:09:05+08:00[Asia/Shanghai]")
        val internship=record("9月25日 16:00 前 · 把数据看板方案发给同事")
        assertEquals("HR_FALSE_MINUTES_URGENCY",HrTimingGuard.contradiction(
            card("距16:00截止仅剩约4分钟"),listOf(internship),day1))
        val day2=at("2026-09-22T15:55:24+08:00[Asia/Shanghai]")
        val assignment=record("计算机网络 Assignment 1 · 9月28日 23:59 前合成 PDF 上传")
        assertEquals("HR_FALSE_TODAY_URGENCY",HrTimingGuard.contradiction(
            card("作业今晚23:59截止"),listOf(assignment),day2))
        assertNull(HrTimingGuard.contradiction(card("9月28日23:59前合成 PDF 上传"),listOf(assignment),day2))
        assertEquals("HR_NO_CURRENT_OPPORTUNITY",HrTimingGuard.noCurrentOpportunity(
            card("9月26日选题碰头前先读 paper_share.pdf"),
            listOf(record("9月26日14:00 选题碰头；待看 paper_share.pdf")),
            setOf("current-frame"),emptySet(),setOf("VISUAL"),day2))
        assertNull(HrTimingGuard.noCurrentOpportunity(card("核对异常展示"),
            listOf(record("9月26日看板评审，需异常展示")),setOf("current-frame"),emptySet(),
            setOf("VISUAL"),day2,candidateHasSpecificVisualLink=true))
        assertNull(HrTimingGuard.noCurrentOpportunity(card("核对当前画面"),listOf(assignment),
            setOf("source"),emptySet(),setOf("VISUAL"),day2))
        assertEquals("HR_INTERNAL_ID_IN_CARD",HrTimingGuard.internalIdLeak(
            card("请核对三个要点（D1-06, D2-08）")))
    }

    @Test fun unrelatedTerminalLogCannotBecomeDashboardDeadlineEvidence() {
        val at=at("2026-09-21T12:09:05+08:00[Asia/Shanghai]")
        val cited=listOf(record("9月25日 16:00 前提交数据看板方案，含指标定义、异常展示和页面草图"))
        val service=HrService("PREPARATION","方案核对","请核对异常展示",listOf("source","frame-205"))
        assertEquals("HR_VISUAL_TASK_MISMATCH",HrTimingGuard.visualTaskMismatch(
            HrVisualNote("显示器正运行紫色终端，日志提示图片描述过长与模型配置错误",listOf("frame-205")),
            cited,setOf("frame-205"),emptySet(),service,at))
        assertNull(HrTimingGuard.visualTaskMismatch(
            HrVisualNote("文档已编辑至3.1数据异常监测章节",listOf("frame-205")),
            cited,setOf("frame-205"),emptySet(),service,at))
    }
}
