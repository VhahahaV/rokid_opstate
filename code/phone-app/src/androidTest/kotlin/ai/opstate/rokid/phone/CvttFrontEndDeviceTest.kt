package ai.opstate.rokid.phone

import ai.opstate.rokid.phone.runtime.CvttFrontEnd
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ai.opstate.protocol.StudyWire
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Optional device parity gate for the frozen v1 release. It uses the 18 supplied frames against the
 * actual ONNX sessions, on the phone, which is where the front end runs now. Running it is opt-in
 * because loading the 115 MB encoder is not a normal smoke check:
 *
 * adb shell am instrument -w -e cvtt true -e class ai.opstate.rokid.phone.CvttFrontEndDeviceTest \
 *   ai.opstate.rokid.phone.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class CvttFrontEndDeviceTest {
    @Test fun suppliedSessionMatchesScoresAndDecisions() {
        assumeTrue("opt-in CVTT parity",InstrumentationRegistry.getArguments().getString("cvtt")=="true")
        val testAssets=InstrumentationRegistry.getInstrumentation().context.assets
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val frameRows=testAssets.open("cvtt/session_full/frames.jsonl").bufferedReader().readLines().filter(String::isNotBlank)
            .map {StudyWire.json.parseToJsonElement(it).jsonObject}
        val expected=testAssets.open("cvtt/session_full/expected_stream.jsonl").bufferedReader().readLines().filter(String::isNotBlank)
            .map {StudyWire.json.parseToJsonElement(it).jsonObject}
        CvttFrontEnd(target).use {frontEnd->
            var elapsed=0L
            frameRows.forEachIndexed {index,row->
                elapsed+=row.getValue("gap_raw_s").jsonPrimitive.content.toDouble().times(1_000).toLong()
                val jpeg=testAssets.open("cvtt/session_full/frames/${index.toString().padStart(3,'0')}.jpg").use {it.readBytes()}
                val decision=frontEnd.observe(jpeg,elapsed)
                val want=expected[index]
                assertEquals("frame $index ensemble score",want.getValue("score_ensemble").jsonPrimitive.content.toFloat(),decision.score,0.005f)
                // The supplied fixture excludes the release's unconditional initialisation packet.
                if(index>0) assertEquals("frame $index decision",want.getValue("fired").jsonPrimitive.content.toBoolean(),decision.fired)
            }
        }
    }
}
