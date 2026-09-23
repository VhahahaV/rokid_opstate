package ai.opstate.rokid.phone

import ai.opstate.rokid.phone.replay.LocalCollectionRepository
import ai.opstate.rokid.phone.runtime.CvttFrontEnd
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The front end's trigger rate, measured on the photographs this phone already holds.
 *
 * The frozen release is calibrated to fire about six times an hour on the material it was frozen
 * against, and the only honest way to know whether this phone's own material sits anywhere near that is
 * to run the same graphs over it. Nothing here asserts a rate - a rate is a measurement, and the number
 * it is compared against lives in a report - but it does assert that the frames were read and judged.
 *
 * Each record is judged in its own front end, because a session gets a fresh one: the first frame of a
 * session is the release's unconditional initialisation packet, and a measurement that forgot it would
 * be measuring a different pipeline from the one the phone runs.
 *
 *   adb shell am instrument -w -e trigger_rate true -e frames 400 [-e session <record id>] \
 *     -e class ai.opstate.rokid.phone.CvttTriggerRateDeviceTest \
 *     ai.opstate.rokid.phone.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class CvttTriggerRateDeviceTest {
    @Test fun theFrozenFrontEndOverThePhotographsThisPhoneHolds()=runBlocking<Unit> {
        assumeTrue("opt-in trigger-rate measurement",InstrumentationRegistry.getArguments().getString("trigger_rate")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=context.applicationContext as PhoneApplication
        app.storageReady.await()
        val arguments=InstrumentationRegistry.getArguments()
        val cap=arguments.getString("frames")?.toIntOrNull() ?: 400
        val dumpScores=arguments.getString("dump_scores")=="true"

        data class Fired(val record: String,val frames: Int,val spanHours: Double,val coldStarts: Int,val visualHits: Int)
        data class Judged(val record: String,val at: Long,val score: Float,val reason: String?,val ms: Long)
        val reports=mutableListOf<Fired>()
        val judged=mutableListOf<Judged>()
        var remaining=cap
        val only=arguments.getString("session")
        val records=withContext(Dispatchers.IO) {app.database.dao().sessions()
            .filter {only==null || it.sessionId==only}.sortedByDescending {it.startedAtEpochMs}}
        for(session in records) {
            if(remaining<=0) break
            val frames=withContext(Dispatchers.IO) {LocalCollectionRepository(app.journal).load(session.sessionId,0).images}
                .sortedBy {it.record.occurredAtEpochMs}.takeLast(remaining)
            if(frames.isEmpty()) continue
            var cold=0;var hits=0
            CvttFrontEnd(context).use {frontEnd->
                val first=frames.first().record.occurredAtEpochMs
                for(frame in frames) {
                    val started=android.os.SystemClock.elapsedRealtime()
                    val decision=frontEnd.observe(frame.jpegFile.readBytes(),frame.record.occurredAtEpochMs-first)
                    // The raw score, not just the decision: the score is a property of the frozen graphs
                    // and does not change with a threshold, so one pass over the material is enough to
                    // price every candidate threshold afterwards.
                    if(dumpScores) judged+=Judged(session.sessionId,frame.record.occurredAtEpochMs-first,decision.score,
                        decision.reason,android.os.SystemClock.elapsedRealtime()-started)
                    when(decision.reason) {
                        "CVTT_COLD_START"->cold++
                        else->if(decision.fired) hits++
                    }
                }
            }
            val span=(frames.last().record.occurredAtEpochMs-frames.first().record.occurredAtEpochMs)/3_600_000.0
            reports+=Fired(session.sessionId,frames.size,span,cold,hits)
            remaining-=frames.size
        }
        assertTrue("no photographs on this device to measure",reports.isNotEmpty())
        val frames=reports.sumOf {it.frames}
        val cold=reports.sumOf {it.coldStarts}
        val hits=reports.sumOf {it.visualHits}
        val span=reports.sumOf {it.spanHours}
        val report=buildJsonObject {
            put("front_end",JsonPrimitive(CvttFrontEnd.VERSION))
            put("frames_judged",JsonPrimitive(frames))
            put("cold_starts",JsonPrimitive(cold))
            put("visual_hits",JsonPrimitive(hits))
            put("covered_hours",JsonPrimitive(span))
            put("visual_hits_per_hour",JsonPrimitive(if(span>0) hits/span else 0.0))
            put("triggers_per_hour_including_cold_start",JsonPrimitive(if(span>0) (hits+cold)/span else 0.0))
            // The rate per hour is the rate per frame times how often frames arrive, so both are kept:
            // a record whose cadence is minutes rather than the calibrated 32 seconds cannot produce the
            // release's hourly figure however well the model behaves.
            put("frames_per_hour",JsonPrimitive(if(span>0) frames/span else 0.0))
            put("hits_per_100_frames",JsonPrimitive(if(frames>0) hits*100.0/frames else 0.0))
            put("hits_per_hour_at_the_calibrated_30s_cadence",JsonPrimitive(if(frames>0) hits*120.0/frames else 0.0))
            put("records",JsonArray(reports.map {row->buildJsonObject {
                put("session_id",JsonPrimitive(row.record))
                put("frames",JsonPrimitive(row.frames))
                put("covered_hours",JsonPrimitive(row.spanHours))
                put("cold_starts",JsonPrimitive(row.coldStarts))
                put("visual_hits",JsonPrimitive(row.visualHits))
            }}))
        }
        val out=File(requireNotNull(context.getExternalFilesDir(null)),"cvtt_trigger_rate.json")
        out.writeText(Json {prettyPrint=true}.encodeToString(JsonObject.serializer(),report))
        if(dumpScores) {
            val lines=judged.joinToString("\n") {row->buildJsonObject {
                put("record",JsonPrimitive(row.record))
                put("t_ms",JsonPrimitive(row.at))
                put("score",JsonPrimitive(row.score))
                put("reason",row.reason?.let(::JsonPrimitive) ?: JsonNull)
                put("judge_ms",JsonPrimitive(row.ms))
            }.toString()}
            File(requireNotNull(context.getExternalFilesDir(null)),"cvtt_frontend_scores.jsonl").writeText(lines)
            val mean=judged.map {it.ms}.average()
            val sorted=judged.map {it.ms}.sorted()
            println("CVTT_FRONTEND_COST per_frame_mean=${"%.0f".format(mean)}ms median=${sorted[sorted.size/2]}ms " +
                "p90=${sorted[(sorted.size*9/10).coerceAtMost(sorted.size-1)]}ms frames=${judged.size}")
        }
        // The measurement itself, on stdout, so a run leaves its number in the transcript too.
        println("CVTT_TRIGGER_RATE $report")
    }
}
