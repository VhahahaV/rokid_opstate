package ai.opstate.rokid.phone

import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.runtime.CvttFrontEnd
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Replays the operator-reviewed two-day photo order through the same CVTT code used by live service.
 * Input contains only source JPEG references, hashes and virtual times; it never imports synthetic context
 * or invokes the paid model. One front-end instance spans both days, as one continuous service would.
 */
@RunWith(AndroidJUnit4::class)
class CvttTwoDayHistoryDeviceTest {
    @Test fun replayReviewedTwoDayHistory()=runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("two_day_cvtt")=="true")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val app=context.applicationContext as PhoneApplication
        app.storageReady.await()
        val outputDir=requireNotNull(context.getExternalFilesDir(null))
        val input=File(outputDir,"cvtt-two-day-input.jsonl")
        assertTrue("CVTT_TWO_DAY_INPUT_MISSING",input.isFile)
        val inputSha=StudyWire.sha256(input.readBytes())
        val scoresFile=File(outputDir,"cvtt_two_day_scores.jsonl")
        val summaryFile=File(outputDir,"cvtt_two_day_summary.json")
        summaryFile.delete()
        var firstAt: Long?=null
        var previousAt=-1L
        var frames=0
        var cold=0
        var hits=0
        val dayCounts=mutableMapOf<Int,Int>()
        val dayHits=mutableMapOf<Int,Int>()
        val sourcePath=Regex("^source/files/sessions/([A-Za-z0-9_-]+)/images/([A-Za-z0-9_-]+\\.jpg)$")
        CvttFrontEnd(context).use {frontEnd->
            scoresFile.bufferedWriter().use {out->
                input.forEachLine {line->
                    if(line.isBlank()) return@forEachLine
                    val row=StudyWire.json.parseToJsonElement(line).jsonObject
                    val order=row.getValue("order").jsonPrimitive.int
                    assertEquals("CVTT_TWO_DAY_ORDER",frames+1,order)
                    val day=row.getValue("day").jsonPrimitive.int
                    val at=row.getValue("virtual_at_epoch_ms").jsonPrimitive.long
                    val zero=firstAt ?: at.also {firstAt=it}
                    assertTrue("CVTT_TWO_DAY_TIME_ORDER",at>previousAt)
                    previousAt=at
                    val source=row.getValue("photo_path").jsonPrimitive.content
                    val match=requireNotNull(sourcePath.matchEntire(source)) {"CVTT_TWO_DAY_SOURCE_PATH"}
                    val file=app.journal.localImage(match.groupValues[1],"images/${match.groupValues[2]}")
                    val jpeg=file.readBytes()
                    assertEquals("CVTT_TWO_DAY_IMAGE_HASH",row.getValue("photo_sha256").jsonPrimitive.content,StudyWire.sha256(jpeg))
                    val began=SystemClock.elapsedRealtime()
                    val decision=frontEnd.observe(jpeg,at-zero)
                    val judgedMs=SystemClock.elapsedRealtime()-began
                    frames++
                    dayCounts[day]=(dayCounts[day] ?: 0)+1
                    if(decision.reason=="CVTT_COLD_START") cold++
                    if(decision.reason=="CVTT_THETA_ON") {
                        hits++
                        dayHits[day]=(dayHits[day] ?: 0)+1
                    }
                    val result=buildJsonObject {
                        put("order",JsonPrimitive(order))
                        put("day",JsonPrimitive(day))
                        put("scene_id",row.getValue("scene_id"))
                        put("virtual_at_epoch_ms",JsonPrimitive(at))
                        put("photo_sha256",row.getValue("photo_sha256"))
                        put("score",JsonPrimitive(decision.score))
                        put("seed_scores",JsonArray(decision.seedScores.map(::JsonPrimitive)))
                        put("reason",decision.reason?.let(::JsonPrimitive) ?: JsonNull)
                        put("fired",JsonPrimitive(decision.fired))
                        put("judge_ms",JsonPrimitive(judgedMs))
                    }
                    out.write(result.toString())
                    out.newLine()
                    if(frames%100==0) {
                        out.flush()
                        instrumentation.sendStatus(1,Bundle().apply {
                            putInt("two_day_cvtt_processed",frames)
                            putInt("two_day_cvtt_visual_hits",hits)
                        })
                    }
                }
            }
        }
        assertEquals("CVTT_TWO_DAY_FRAME_COUNT",1772,frames)
        assertEquals("CVTT_TWO_DAY_DAY1_COUNT",866,dayCounts[1])
        assertEquals("CVTT_TWO_DAY_DAY2_COUNT",906,dayCounts[2])
        assertEquals("CVTT_TWO_DAY_ONE_COLD_START",1,cold)
        val summary=buildJsonObject {
            put("front_end",JsonPrimitive(CvttFrontEnd.VERSION))
            put("input_sha256",JsonPrimitive(inputSha))
            put("frames_judged",JsonPrimitive(frames))
            put("cold_starts",JsonPrimitive(cold))
            put("visual_hits",JsonPrimitive(hits))
            put("day1_visual_hits",JsonPrimitive(dayHits[1] ?: 0))
            put("day2_visual_hits",JsonPrimitive(dayHits[2] ?: 0))
        }
        summaryFile.writeText(Json {prettyPrint=true}.encodeToString(JsonObject.serializer(),summary))
        println("CVTT_TWO_DAY_HISTORY $summary")
    }
}
