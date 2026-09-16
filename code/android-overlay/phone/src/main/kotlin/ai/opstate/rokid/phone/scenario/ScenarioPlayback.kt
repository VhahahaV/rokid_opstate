package ai.opstate.rokid.phone.scenario

data class ScenarioMarker(val positionMs: Long,val label: String)
data class ScenarioTimeline(val durationMs: Long,val markers: List<ScenarioMarker>)

fun StudyScenarioDto.timeline(): ScenarioTimeline {
    val markers=events.filter {it.runtimeDelivery=="SHOW_CONTEXT_CARD" && it.availableAtOffsetMs>=0}
        .sortedBy {it.availableAtOffsetMs}.map {ScenarioMarker(it.availableAtOffsetMs,it.publicText)}
    return ScenarioTimeline(maxOf(1_000L,markers.maxOfOrNull {it.positionMs} ?: 0L,
        episodes.mapNotNull {it.deadlineOffsetMs}.maxOrNull() ?: 0L),markers)
}

/** Controls public-event playback only. Camera/model/receipt timestamps remain actual wall time. */
class ScenarioPlayback(private val initialMs: Long,private val startedElapsedMs: Long,val durationMs: Long) {
    private var pausedAt: Long?=null
    private var pausedDuration=0L
    init {require(initialMs in 0..durationMs)}
    fun position(nowElapsedMs: Long): Long=(initialMs+(pausedAt ?: nowElapsedMs)-startedElapsedMs-pausedDuration).coerceIn(0,durationMs)
    fun pause(nowElapsedMs: Long) {if(pausedAt==null) pausedAt=nowElapsedMs}
    fun resume(nowElapsedMs: Long) {pausedAt?.let {pausedDuration+=(nowElapsedMs-it).coerceAtLeast(0)};pausedAt=null}
}
