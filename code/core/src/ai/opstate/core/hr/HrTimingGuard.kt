package ai.opstate.core.hr

import ai.opstate.core.model.ObservationRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Rejects a small class of checkable false-urgency claims before a card can reach the glasses. */
object HrTimingGuard {
    private val monthDay=Regex("(?<![0-9])([1-9]|1[0-2])月([1-9]|[12][0-9]|3[01])日")
    private val fullDate=Regex("(?<![0-9])(20[0-9]{2})[-/]([01]?[0-9])[-/]([0-3]?[0-9])")

    private fun topicTerms(text: String): Set<String> {
        val han=Regex("[\\p{IsHan}]+").findAll(text).flatMap {match->
            (0 until match.value.length-1).asSequence().map {match.value.substring(it,it+2)}
        }
        val latin=Regex("[A-Za-z0-9_]{3,}").findAll(text).map {it.value.lowercase()}
        return (han+latin).filter {it !in setOf("数据","当前","今天","明天","正在","显示","画面",
            "屏幕","电脑","手机","桌面","工位","方案","任务","截止","需要","处理","用户","文档",
            "可以","时间","之前","一份","这个","一个","已经")}.toSet()
    }

    fun specificVisualLink(note: HrVisualNote, cited: List<ObservationRecord>): Boolean {
        if(note.text.isBlank()) return false
        val old=cited.filter {it.kind!=ai.opstate.core.model.ObservationKind.IMAGE &&
            it.kind!=ai.opstate.core.model.ObservationKind.MODEL_NOTE}
        return old.any {topicTerms(note.text).intersect(topicTerms(it.content)).isNotEmpty()}
    }

    private fun dates(cited: List<ObservationRecord>, today: LocalDate): Set<LocalDate> = buildSet {
        for(record in cited) {
            for(match in fullDate.findAll(record.content)) runCatching {
                LocalDate.of(match.groupValues[1].toInt(),match.groupValues[2].toInt(),match.groupValues[3].toInt())
            }.getOrNull()?.let(::add)
            for(match in monthDay.findAll(record.content)) runCatching {
                LocalDate.of(today.year,match.groupValues[1].toInt(),match.groupValues[2].toInt())
            }.getOrNull()?.let(::add)
        }
    }

    fun contradiction(service: HrService, cited: List<ObservationRecord>, snapshotAtEpochMs: Long): String? {
        val today=Instant.ofEpochMilli(snapshotAtEpochMs).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()
        val supportedDates=dates(cited,today)
        if(supportedDates.size != 1) return null
        val days=ChronoUnit.DAYS.between(today,supportedDates.single())
        val words=service.title+" "+service.body
        if(Regex("今天|今晚|今日|本日").containsMatchIn(words) && days != 0L) return "HR_FALSE_TODAY_URGENCY"
        if(Regex("明天|明晚|次日").containsMatchIn(words) && days != 1L) return "HR_FALSE_TOMORROW_URGENCY"
        if(Regex("只剩.{0,8}分钟|仅剩.{0,8}分钟|还有.{0,8}分钟|距.{0,20}仅剩").containsMatchIn(words) && days != 0L)
            return "HR_FALSE_MINUTES_URGENCY"
        if(Regex("即将截止|截止临近|迫在眉睫").containsMatchIn(words) && days > 1L)
            return "HR_FALSE_NEAR_DEADLINE"
        return null
    }

    /** A visual packet with only old records is not, by itself, a moment to interrupt. */
    fun noCurrentOpportunity(service: HrService, cited: List<ObservationRecord>,
                             currentImageIds: Set<String>, newDigitalIds: Set<String>,
                             triggerTypes: Set<String>, snapshotAtEpochMs: Long,
                             candidateHasSpecificVisualLink: Boolean=false): String? {
        if("MANUAL" in triggerTypes || candidateHasSpecificVisualLink ||
            service.evidenceIds.any {it in currentImageIds || it in newDigitalIds}) return null
        val today=Instant.ofEpochMilli(snapshotAtEpochMs).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()
        val urgent=dates(cited,today).any {ChronoUnit.DAYS.between(today,it) in 0L..1L}
        return if(urgent) null else "HR_NO_CURRENT_OPPORTUNITY"
    }

    fun internalIdLeak(service: HrService): String? =
        if(Regex("\\bD[0-9]+-[0-9]+\\b|\\bframe-[0-9]+\\b|\\bobs-[A-Za-z0-9_-]+\\b|history-context-")
                .containsMatchIn(service.title+" "+service.body)) "HR_INTERNAL_ID_IN_CARD" else null

    /** A visible technical error is not automatically an error in an unrelated recorded project. */
    fun visualTaskMismatch(note: HrVisualNote, cited: List<ObservationRecord>,
                           currentImageIds: Set<String>, newDigitalIds: Set<String>,
                           service: HrService, snapshotAtEpochMs: Long): String? {
        if(service.evidenceIds.none {it in currentImageIds} || service.evidenceIds.any {it in newDigitalIds}) return null
        if(!Regex("终端|日志|报错|配置错误|脚本|文件路径|图片描述").containsMatchIn(note.text)) return null
        val oldRecords=cited.filter {it.kind != ai.opstate.core.model.ObservationKind.IMAGE &&
            it.kind != ai.opstate.core.model.ObservationKind.MODEL_NOTE}
        if(oldRecords.isEmpty()) return null
        val today=Instant.ofEpochMilli(snapshotAtEpochMs).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()
        if(dates(oldRecords,today).any {ChronoUnit.DAYS.between(today,it) in 0L..1L}) return null
        return if(!specificVisualLink(note,oldRecords)) "HR_VISUAL_TASK_MISMATCH" else null
    }
}
